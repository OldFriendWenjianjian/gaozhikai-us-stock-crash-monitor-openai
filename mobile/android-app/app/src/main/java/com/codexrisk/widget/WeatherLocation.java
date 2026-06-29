package com.codexrisk.widget;

final class WeatherLocation {
    static final WeatherLocation DEVICE = new WeatherLocation("device", "当前定位", 0, 0, true);
    static final WeatherLocation[] PRESETS = new WeatherLocation[] {
            new WeatherLocation("beijing", "北京", 39.9042, 116.4074, false),
            new WeatherLocation("shanghai", "上海", 31.2304, 121.4737, false),
            new WeatherLocation("shenzhen", "深圳", 22.5431, 114.0579, false),
            new WeatherLocation("guangzhou", "广州", 23.1291, 113.2644, false),
            new WeatherLocation("hangzhou", "杭州", 30.2741, 120.1551, false),
            new WeatherLocation("chengdu", "成都", 30.5728, 104.0668, false),
    };

    final String id;
    final String label;
    final double latitude;
    final double longitude;
    final boolean device;

    WeatherLocation(String id, String label, double latitude, double longitude, boolean device) {
        this.id = id;
        this.label = label;
        this.latitude = latitude;
        this.longitude = longitude;
        this.device = device;
    }

    static WeatherLocation byId(String id) {
        if (id == null || id.isEmpty() || DEVICE.id.equals(id)) {
            return DEVICE;
        }
        for (WeatherLocation location : PRESETS) {
            if (location.id.equals(id)) {
                return location;
            }
        }
        return DEVICE;
    }
}
