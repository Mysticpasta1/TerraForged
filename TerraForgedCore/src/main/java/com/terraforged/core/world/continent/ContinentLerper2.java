package com.terraforged.core.world.continent;

import com.terraforged.core.cell.Cell;
import com.terraforged.core.cell.Populator;
import com.terraforged.core.world.terrain.Terrain;
import me.dags.noise.func.Interpolation;
import me.dags.noise.util.NoiseUtil;

public class ContinentLerper2 implements Populator {

    private final Populator lower;
    private final Populator upper;

    private final float blendLower;
    private final float blendUpper;
    private final float blendRange;
    private final float midpoint;
    private final float tagThreshold;

    public ContinentLerper2(Populator lower, Populator upper, float min, float max, float split, float tagThreshold) {
        this.lower = lower;
        this.upper = upper;
        this.blendLower = min;
        this.blendUpper = max;
        this.blendRange = blendUpper - blendLower;
        this.midpoint = blendLower + (blendRange * split);
        this.tagThreshold = tagThreshold;
    }

    @Override
    public void apply(Cell<Terrain> cell, float x, float y) {
        float select = cell.continentEdge;

        if (select < blendLower) {
            lower.apply(cell, x, y);
            return;
        }

        if (select > blendUpper) {
            upper.apply(cell, x, y);
            return;
        }

        float alpha = Interpolation.LINEAR.apply((select - blendLower) / blendRange);
        lower.apply(cell, x, y);

        float lowerVal = cell.value;
        Terrain lowerType = cell.tag;

        upper.apply(cell, x, y);
        float upperVal = cell.value;

        cell.value = NoiseUtil.lerp(lowerVal, upperVal, alpha);
        if (select < midpoint) {
            cell.tag = lowerType;
        }
    }

    @Override
    public void tag(Cell<Terrain> cell, float x, float y) {
        float select = cell.continentEdge;
        if (select < blendLower) {
            lower.tag(cell, x, y);
            return;
        }

        if (select > blendUpper) {
            upper.tag(cell, x, y);
            return;
        }

        if (select < tagThreshold) {
            lower.tag(cell, x, y);
        } else {
            upper.tag(cell, x, y);
        }
    }
}
