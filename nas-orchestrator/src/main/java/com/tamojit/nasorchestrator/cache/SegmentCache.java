package com.tamojit.nasorchestrator.cache;

public interface SegmentCache {
    byte[] get(String key);

    void put(String key, byte[] data);
}
