package dev.luxloader.api.pipeline;

/** World-space rotation shared by plugin lighting and the prepared celestial sky draw. */
public record CelestialRotation(float x, float y, float z, float w) {
    public static final CelestialRotation IDENTITY = new CelestialRotation(0, 0, 0, 1);

    public CelestialRotation {
        double length = Math.sqrt((double) x*x + (double) y*y + (double) z*z + (double) w*w);
        if (!Double.isFinite(length) || length < 1e-12) {
            throw new IllegalArgumentException("Celestial rotation must be a finite, nonzero quaternion");
        }
        x /= length;
        y /= length;
        z /= length;
        w /= length;
    }

    /** Rotate a direction without modifying the host environment snapshot. */
    public float[] transform(float[] direction) {
        float dx = direction[0], dy = direction[1], dz = direction[2];
        float tx = 2 * (y*dz - z*dy);
        float ty = 2 * (z*dx - x*dz);
        float tz = 2 * (x*dy - y*dx);
        return new float[] {dx + w*tx + y*tz - z*ty,
                dy + w*ty + z*tx - x*tz, dz + w*tz + x*ty - y*tx};
    }
}
