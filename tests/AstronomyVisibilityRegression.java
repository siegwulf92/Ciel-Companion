import com.cielcompanion.astronomy.AstronomyVisibility;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Small no-network regression test for daylight/twilight and weather gates. */
public final class AstronomyVisibilityRegression {
    private static final ZoneId ZONE = ZoneId.of("America/New_York");

    public static void main(String[] args) {
        LocalDate day = LocalDate.of(2026, 10, 29);
        AstronomyVisibility.SolarWindow daylight = AstronomyVisibility.evaluate(
                ZonedDateTime.of(day, LocalTime.of(13, 0), ZONE), LocalTime.of(7, 40), LocalTime.of(18, 20));
        require(daylight.daylight() && !daylight.nightSky(), "daylight classification");

        AstronomyVisibility.SolarWindow sunrise = AstronomyVisibility.evaluate(
                ZonedDateTime.of(day, LocalTime.of(8, 5), ZONE), LocalTime.of(7, 40), LocalTime.of(18, 20));
        require(sunrise.twilight(), "sunrise twilight classification");

        AstronomyVisibility.SolarWindow night = AstronomyVisibility.evaluate(
                ZonedDateTime.of(day, LocalTime.of(22, 0), ZONE), LocalTime.of(7, 40), LocalTime.of(18, 20));
        require(night.nightSky() && !night.daylight(), "night classification");

        require(AstronomyVisibility.skyObscuredByWeather("Heavy rain"), "rain weather gate");
        require(!AstronomyVisibility.skyObscuredByWeather("Clear"), "clear weather gate");
        require(AstronomyVisibility.skyObscuredByWeather("Clear", 95, 10.0), "cloud-cover weather gate");
        require(AstronomyVisibility.describeSkyConditions("Partly cloudy", 55, 8.0)
                .contains("Bright planets"), "sky-quality explanation");
        System.out.println("Astronomy visibility regression passed.");
    }

    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label + " failed");
    }
}
