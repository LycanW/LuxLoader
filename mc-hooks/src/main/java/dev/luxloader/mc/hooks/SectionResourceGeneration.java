package dev.luxloader.mc.hooks;

/** Resource-stack identity captured around section compilation, before upload can be delayed. */
public interface SectionResourceGeneration {
    long luxloader$resourceGeneration();
    void luxloader$resourceGeneration(long generation);
}
