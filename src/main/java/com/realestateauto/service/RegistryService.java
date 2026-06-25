package com.realestateauto.service;

import com.realestateauto.automation.IrosAutomation;
import com.realestateauto.config.AppConfig;

import java.util.function.Consumer;

public class RegistryService {

    private final AppConfig config;

    public RegistryService(AppConfig config) {
        this.config = config;
    }

    public void download(String address, String savePath, Consumer<String> logger) throws Exception {
        String irosId = config.get("iros.id");
        String irosPassword = config.get("iros.password");
        String paymentAccount = config.get("iros.paymentAccount");
        String paymentPassword = config.get("iros.paymentPassword");
        IrosAutomation automation = new IrosAutomation(
            savePath, irosId, irosPassword, paymentAccount, paymentPassword, logger);
        automation.download(address);
    }
}
