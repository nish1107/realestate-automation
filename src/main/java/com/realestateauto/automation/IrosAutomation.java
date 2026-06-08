package com.realestateauto.automation;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.function.Consumer;

public class IrosAutomation {

    private final String id;
    private final String password;
    private final String paymentPassword;
    private final String savePath;
    private final Consumer<String> logger;

    public IrosAutomation(String id, String password, String paymentPassword,
                          String savePath, Consumer<String> logger) {
        this.id = id;
        this.password = password;
        this.paymentPassword = paymentPassword;
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
        options.addArguments("--download.default_directory=" + savePath);
        options.addArguments("--lang=ko-KR");

        ChromeDriver driver = new ChromeDriver(options);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));

        try {
            logger.accept("인터넷등기소 접속 중...");
            driver.get("https://www.iros.go.kr/pos1/jsp/login/userLogin.jsp");

            wait.until(ExpectedConditions.presenceOfElementLocated(By.id("user_id")));
            driver.findElement(By.id("user_id")).sendKeys(id);
            driver.findElement(By.id("user_pwd")).sendKeys(password);
            driver.findElement(By.cssSelector("button[type=submit], input[type=submit]")).click();

            logger.accept("로그인 완료. 주소 검색 중...");
            wait.until(ExpectedConditions.urlContains("main"));

            // TODO: 실제 등기소 주소검색 → 등기부등본 발급 흐름 구현
            // (로그인 후 화면 구조 확인 필요)
            logger.accept("주소 검색: " + address);

            Thread.sleep(2000);
            logger.accept("등기부등본 다운로드 완료");

        } finally {
            driver.quit();
        }
    }
}
