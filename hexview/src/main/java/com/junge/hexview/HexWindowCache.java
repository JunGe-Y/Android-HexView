package com.junge.hexview;


final class HexWindowCache {
    private final HexRenderModel[] cache = new HexRenderModel[3];
    private final int[] starts = new int[]{-1, -1, -1};
    private final int[] ends = new int[]{-1, -1, -1};


    void clear() {
        for (int i = 0; i < cache.length; i++) {
            cache[i] = null;
            starts[i] = -1;
            ends[i] = -1;
        }
    }


    boolean contains(int start, int end) {
        return getCovering(start, end) != null;

    }



    HexRenderModel getCovering(int start, int end) {
        for (int i = 0; i < cache.length; i++) {
            if (cache[i] != null && start >= starts[i] && end <= ends[i]) {
                return cache[i];
            }
        }
        return null;
    }

    void put(HexRenderModel model) {
        cache[2] = cache[1];
        starts[2] = starts[1];
        ends[2] = ends[1];
        cache[1] = cache[0];
        starts[1] = starts[0];
        ends[1] = ends[0];
        cache[0] = model;
        starts[0] = model.startOffset;
        ends[0] = model.getWindowEndOffset();
    }
}


