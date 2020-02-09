package com.terraforged.core.world.heightmap;

import com.terraforged.core.cell.Cell;
import com.terraforged.core.cell.Populator;
import com.terraforged.core.module.Blender;
import com.terraforged.core.settings.GeneratorSettings;
import com.terraforged.core.settings.Settings;
import com.terraforged.core.util.Seed;
import com.terraforged.core.world.GeneratorContext;
import com.terraforged.core.world.climate.Climate;
import com.terraforged.core.world.continent.ContinentLerper2;
import com.terraforged.core.world.continent.ContinentLerper3;
import com.terraforged.core.world.continent.ContinentModule;
import com.terraforged.core.world.terrain.region.RegionLerper;
import com.terraforged.core.world.terrain.region.RegionModule;
import com.terraforged.core.world.terrain.region.RegionSelector;
import com.terraforged.core.world.river.RiverManager;
import com.terraforged.core.world.terrain.Terrain;
import com.terraforged.core.world.terrain.TerrainPopulator;
import com.terraforged.core.world.terrain.Terrains;
import com.terraforged.core.world.terrain.provider.TerrainProvider;
import me.dags.noise.Module;
import me.dags.noise.Source;
import me.dags.noise.func.EdgeFunc;
import me.dags.noise.func.Interpolation;

public class WorldHeightmap implements Heightmap {

    private static final float DEEP_OCEAN_VALUE = 0.2F;
    private static final float OCEAN_VALUE = 0.3F;
    private static final float BEACH_VALUE = 0.34F;
    private static final float COAST_VALUE = 0.4F;
    private static final float INLAND_VALUE = 0.6F;

    private final Levels levels;
    private final Terrains terrain;
    private final Settings settings;

    private final Populator continentModule;
    private final Populator regionModule;

    private final Climate climate;
    private final Populator root;
    private final RiverManager riverManager;
    private final TerrainProvider terrainProvider;

    public WorldHeightmap(GeneratorContext context) {
        context = context.copy();

        this.levels = context.levels;
        this.terrain = context.terrain;
        this.settings = context.settings;
        this.climate = new Climate(context);

        Seed seed = context.seed;
        Levels levels = context.levels;
        GeneratorSettings genSettings = context.settings.generator;

        Seed regionSeed = seed.nextSeed();
        Seed regionWarp = seed.nextSeed();

        int regionWarpScale = 400;
        int regionWarpStrength = 200;
        RegionConfig regionConfig = new RegionConfig(
                regionSeed.get(),
                context.settings.generator.land.regionSize,
                Source.simplex(regionWarp.next(), regionWarpScale, 1),
                Source.simplex(regionWarp.next(), regionWarpScale, 1),
                regionWarpStrength
        );

        regionModule = new RegionModule(regionConfig);

        // controls where mountain chains form in the world
        Module mountainShapeBase = Source.cellEdge(seed.next(), genSettings.land.mountainScale, EdgeFunc.DISTANCE_2_ADD)
                .add(Source.cubic(seed.next(), genSettings.land.mountainScale, 1).scale(-0.05));

        // sharpens the transition to create steeper mountains
        Module mountainShape = mountainShapeBase
                .curve(Interpolation.CURVE3)
                .clamp(0, 0.9)
                .map(0, 1);

        terrainProvider = context.terrainFactory.create(context, regionConfig, this);

        // the voronoi controlled terrain regions
        Populator terrainRegions = new RegionSelector(terrainProvider.getPopulators());
        // the terrain type at region edges
        Populator terrainRegionBorders = new TerrainPopulator(terrainProvider.getLandforms().plains(seed), context.terrain.steppe);

        // transitions between the unique terrain regions and the common border terrain
        Populator terrain = new RegionLerper(terrainRegionBorders, terrainRegions);

        // mountain populator
        Populator mountains = register(terrainProvider.getLandforms().mountains(seed), context.terrain.mountains);

        // controls what's ocean and what's land
        continentModule = new ContinentModule(seed, settings.generator);

        // blends between normal terrain and mountain chains
        Populator land = new Blender(
                mountainShape,
                terrain,
                mountains,
                0.1F,
                0.9F,
                0.6F
        );

        // uses the continent noise to blend between deep ocean, to ocean, to coast
        ContinentLerper3 oceans = new ContinentLerper3(
                climate,
                register(terrainProvider.getLandforms().deepOcean(seed.next()), context.terrain.deepOcean),
                register(Source.constant(levels.water(-7)), context.terrain.ocean),
                register(Source.constant(levels.water), context.terrain.coast),
                DEEP_OCEAN_VALUE, // below == deep, above == transition to shallow
                OCEAN_VALUE,  // below == transition to deep, above == transition to coast
                COAST_VALUE   // below == transition to shallow, above == coast
        );

        // blends between the ocean/coast terrain and land terrains
        root = new ContinentLerper2(
                oceans,
                land,
                OCEAN_VALUE, // below == pure ocean
                INLAND_VALUE, // above == pure land
                COAST_VALUE, // split point
                COAST_VALUE - 0.05F
        );

        riverManager = new RiverManager(this, context);
    }

    @Override
    public void visit(Cell<Terrain> cell, float x, float z) {
        continentModule.apply(cell, x, z);
        regionModule.apply(cell, x, z);

        root.apply(cell, x, z);
    }

    @Override
    public void apply(Cell<Terrain> cell, float x, float z) {
        // initial type
        cell.tag = terrain.steppe;

        // basic shapes
        continentModule.apply(cell, x, z);
        regionModule.apply(cell, x, z);

        // apply actuall heightmap
        root.apply(cell, x, z);

        // apply rivers
        riverManager.apply(cell, x, z);

        // apply climate data
        if (cell.value <= levels.water) {
            climate.apply(cell, x, z, false);
            if (cell.tag == terrain.coast) {
                cell.tag = terrain.ocean;
            }
        } else {
            int range = settings.generator.biomeEdgeNoise.strength;
            float dx = climate.getOffsetX(x, z, range);
            float dz = climate.getOffsetZ(x, z, range);
            float px = x + dx;
            float pz = z + dz;
            tag(cell, px, pz);
            climate.apply(cell, px, pz, false);
            climate.apply(cell, x, z, true);
        }
    }

    @Override
    public void tag(Cell<Terrain> cell, float x, float z) {
        continentModule.apply(cell, x, z);
        regionModule.apply(cell, x, z);
        root.tag(cell, x, z);
    }

    public Climate getClimate() {
        return climate;
    }

    public Populator getPopulator(Terrain terrain) {
        return terrainProvider.getPopulator(terrain);
    }

    private TerrainPopulator register(Module module, Terrain terrain) {
        TerrainPopulator populator = new TerrainPopulator(module, terrain);
        terrainProvider.registerMixable(populator);
        return populator;
    }
}
