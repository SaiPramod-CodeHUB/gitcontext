package com.saipramod.gitcontext.index;

/** Turns text into a dense, L2-normalised vector. */
public interface Embedder {

    float[] embed(String text);

    String name();

    static float[] normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += x * x;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0) {
            return v;
        }
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] / norm);
        }
        return v;
    }
}
