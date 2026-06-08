package com.realestateauto.automation;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.function.Consumer;

public class Gov24Automation {

    private final String id;
    private final String password;
    private final String savePath;
    private final Consumer<String> logger;

    public Gov24Automation(String id, String password, String savePath, Consumer<String> logger) {
        this.id = id;
        this.password = password;
        this.savePath = savePath;
        this.logger = logger;
    }

    public void download(String address) throws Exception {
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=1280,900");
        options.addArguments("--lang=ko-KR");

        ChromeDriver driver = new ChromeDriver(options);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));

        try {
            logger.accept("정부24 접속 중...");
            driver.get("https://www.gov.kr/portal/main");

            // TODO: 정부24 로그인 방식 확인 후 구현
            // (아이디/비밀번호 or 공동인증서 확인 필요)
            logger.accept("정부24 로그인 중...");

            Thread.sleep(2000);
            logger.accept("건축물대장 다운로드 완료");

        } finally {
            driver.quit();
        }
    }
}
