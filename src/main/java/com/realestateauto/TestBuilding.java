package com.realestateauto;

import com.realestateauto.config.AppConfig;
import com.realestateauto.service.BuildingService;

public class TestBuilding {
    public static void main(String[] args) throws Exception {
        String address = "방이동 42-1 101호";
        AppConfig config = new AppConfig();
        config.set("gov24.addressType", "지번");
        String savePath = config.get("savePath").isEmpty()
            ? System.getProperty("user.home") + "\\Desktop\\부동산서류"
            : config.get("savePath");

        System.out.println("=== 건축물대장 테스트: " + address + " ===");
        System.out.println("저장 경로: " + savePath);
        new java.io.File(savePath).mkdirs();

        new BuildingService(config).download(address, savePath, msg -> {
            System.out.println("[" + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg);
            System.out.flush();
        });
    }
}
