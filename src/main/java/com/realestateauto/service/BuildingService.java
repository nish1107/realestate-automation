package com.realestateauto.service;

import com.realestateauto.automation.Gov24Automation;
import com.realestateauto.config.AppConfig;

import java.util.function.Consumer;

public class BuildingService {

    private final AppConfig config;

    public BuildingService(AppConfig config) {
        this.config = config;
    }

    public void download(String address, String savePath, Consumer<String> logger) throws Exception {
        String addressType = config.get("gov24.addressType");
        if (addressType == null || addressType.isEmpty()) addressType = "도로명";
        Gov24Automation automation = new Gov24Automation(
                config.get("gov24.id"),
                config.get("gov24.password"),
                savePath,
                addressType,
                logger
        );
        automation.download(address);
    }
}
