import com.cielcompanion.ai.WeatherAwareAstronomyEngine;
import com.cielcompanion.service.AstronomyService.AstronomyReport;
import com.cielcompanion.service.WeatherService.SkyConditions;
import java.util.Map;
import java.util.List;
public class AstronomyWeatherFilterRegression {
 public static void main(String[] args){
  var report=new AstronomyReport(Map.of("planet_0","Neptune", "constellation_0","Orion", "deep_sky_0","cluster", "special_alignment","alignment", "upcoming_sky_0","Future dated forecast"),List.of("Current moon"),List.of("Seasonal atmosphere"));
  for(var sky:new SkyConditions[]{null,new SkyConditions("Clear",0,10,true,1000),new SkyConditions("Cloudy",90,10,false,1000),new SkyConditions("Clear",0,10,false,1100)}){
   var filtered=WeatherAwareAstronomyEngine.filterForConditions(report,sky,1000);
   if(filtered.sequentialEvents().containsKey("planet_0")||filtered.sequentialEvents().containsKey("constellation_0")||filtered.sequentialEvents().containsKey("deep_sky_0")||filtered.sequentialEvents().containsKey("special_alignment")||!filtered.reportAmbientLines().isEmpty())throw new AssertionError("unverified visual survived");
   if(!filtered.sequentialEvents().containsKey("upcoming_sky_0"))throw new AssertionError("forecast removed");
  }
  if(WeatherAwareAstronomyEngine.filterForConditions(report,new SkyConditions("Clear",0,10,false,1000),1000)!=report)throw new AssertionError("clear measured report lost");
  System.out.println("Astronomy weather filter regression passed");
 }
}
