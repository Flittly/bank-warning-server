package com.yangtze.bankwarning.ai.tool;

import com.yangtze.bankwarning.ai.service.WeatherService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 天气信息工具
 * 暴露给 ReActAgent，用于查询研究区实时天气、预报、预警
 * 数据源：和风天气 API
 */
public class WeatherTools {

    private static final Logger log = LoggerFactory.getLogger(WeatherTools.class);
    private final WeatherService weatherService;

    public WeatherTools(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Tool(name = "get_weather_forecast",
          description = "查询指定经纬度未来 N 天的天气信息，包含实时天气、未来预报和当前生效预警。days 范围 1-7。当研究区有中高风险断面时，强烈建议调用此工具以评估降雨对崩岸风险的影响。")
    public String getWeatherForecast(
            @ToolParam(name = "lng", description = "经度，例如 119.6") Double lng,
            @ToolParam(name = "lat", description = "纬度，例如 32.4") Double lat,
            @ToolParam(name = "days", description = "预报天数，范围 1-7") Integer days) {
        log.info("[tool] get_weather_forecast, lng={}, lat={}, days={}", lng, lat, days);
        if (lng == null || lat == null) {
            return "参数错误：lng 和 lat 不能为空。";
        }
        int d = days == null ? 3 : Math.max(1, Math.min(7, days));
        return weatherService.getFullForecast(lng, lat, d);
    }

    @Tool(name = "get_weather_warning",
          description = "查询指定经纬度当前生效的天气预警（暴雨、台风、大风等）。不需要 days 参数，只返回当前预警。")
    public String getWeatherWarning(
            @ToolParam(name = "lng", description = "经度") Double lng,
            @ToolParam(name = "lat", description = "纬度") Double lat) {
        log.info("[tool] get_weather_warning, lng={}, lat={}", lng, lat);
        if (lng == null || lat == null) {
            return "参数错误：lng 和 lat 不能为空。";
        }
        return weatherService.getActiveWarning(lng, lat);
    }
}
