package com.cielcompanion.astronomy;

import java.util.List;

/** Fixed-star and deep-sky anchors used by the location/time visibility scan. */
public final class SkyCatalog {
    public enum Kind { STAR, CLUSTER, GALAXY }
    public record Object(String id, String name, double raDeg, double decDeg,
                         double magnitude, Kind kind, String constellation,
                         boolean nakedEye) {
        public CombinedAstronomyData.PlanetCoordinate coordinate(long capturedAt) {
            return new CombinedAstronomyData.PlanetCoordinate(id, name, raDeg, decDeg,
                    Double.NaN, Double.NaN, magnitude, Double.NaN, null, Double.NaN,
                    capturedAt, constellation);
        }
    }
    private static final List<Object> OBJECTS = List.of(
            new Object("polaris", "Polaris", 37.9546, 89.2641, 1.98, Kind.STAR, "Ursa Minor", true),
            new Object("sirius", "Sirius", 101.2872, -16.7161, -1.46, Kind.STAR, "Canis Major", true),
            new Object("canopus", "Canopus", 95.9879, -52.6957, -0.74, Kind.STAR, "Carina", true),
            new Object("arcturus", "Arcturus", 213.9153, 19.1824, -0.05, Kind.STAR, "Boötes", true),
            new Object("vega", "Vega", 279.2347, 38.7837, 0.03, Kind.STAR, "Lyra", true),
            new Object("capella", "Capella", 79.1723, 45.9979, 0.08, Kind.STAR, "Auriga", true),
            new Object("rigel", "Rigel", 78.6345, -8.2016, 0.13, Kind.STAR, "Orion", true),
            new Object("procyon", "Procyon", 114.8255, 5.2250, 0.34, Kind.STAR, "Canis Minor", true),
            new Object("betelgeuse", "Betelgeuse", 88.7929, 7.4071, 0.42, Kind.STAR, "Orion", true),
            new Object("antares", "Antares", 247.3519, -26.4320, 1.06, Kind.STAR, "Scorpius", true),
            new Object("altair", "Altair", 297.6958, 8.8683, 0.77, Kind.STAR, "Aquila", true),
            new Object("aldebaran", "Aldebaran", 68.9800, 16.5093, 0.85, Kind.STAR, "Taurus", true),
            new Object("spica", "Spica", 201.2983, -11.1614, 0.98, Kind.STAR, "Virgo", true),
            new Object("pollux", "Pollux", 116.3295, 28.0262, 1.14, Kind.STAR, "Gemini", true),
            new Object("fomalhaut", "Fomalhaut", 344.4128, -29.6222, 1.16, Kind.STAR, "Piscis Austrinus", true),
            new Object("deneb", "Deneb", 310.3579, 45.2803, 1.25, Kind.STAR, "Cygnus", true),
            new Object("regulus", "Regulus", 152.0929, 11.9672, 1.35, Kind.STAR, "Leo", true),
            new Object("castor", "Castor", 113.6494, 31.8883, 1.58, Kind.STAR, "Gemini", true),
            new Object("alnilam", "Alnilam", 84.0533, -1.2019, 1.69, Kind.STAR, "Orion", true),
            new Object("alnitak", "Alnitak", 85.1897, -1.9426, 1.74, Kind.STAR, "Orion", true),
            new Object("bellatrix", "Bellatrix", 81.2828, 6.3497, 1.64, Kind.STAR, "Orion", true),
            new Object("algieba", "Algieba", 154.9931, 19.8415, 2.01, Kind.STAR, "Leo", true),
            new Object("algol", "Algol", 47.0422, 40.9556, 2.12, Kind.STAR, "Perseus", true),
            new Object("mizar", "Mizar", 200.9814, 54.9254, 2.23, Kind.STAR, "Ursa Major", true),
            new Object("pleiades", "the Pleiades", 56.7500, 24.1167, 1.60, Kind.CLUSTER, "Taurus", true),
            new Object("beehive", "the Beehive Cluster", 130.1000, 19.6700, 3.70, Kind.CLUSTER, "Cancer", false),
            new Object("andromeda", "the Andromeda Galaxy", 10.6847, 41.2687, 3.44, Kind.GALAXY, "Andromeda", false)
    );
    private SkyCatalog() {}
    public static List<Object> notableObjects() { return OBJECTS; }
    public static Object byId(String id) { return id == null ? null : OBJECTS.stream().filter(o -> id.equalsIgnoreCase(o.id())).findFirst().orElse(null); }
    public static boolean isFixedObject(String id) { return byId(id) != null; }
}
