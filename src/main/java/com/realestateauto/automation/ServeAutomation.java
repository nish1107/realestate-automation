package com.realestateauto.automation;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

public class ServeAutomation {

    private final String id;
    private final String password;
    private final List<String> filePaths;
    private final Consumer<String> logger;

    public ServeAutomation(String id, String password, List<String> filePaths, Consumer<String> logger) {
        this.id = id;
        this.password = password;
        this.filePaths = filePaths;
        this.logger = logger;
    }

    public void openListingPage() throws Exception {
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        // 써브는 화면에 보여야 하므로 headless 미사용
        options.addArguments("--window-size=1280,900");
        options.addArguments("--lang=ko-KR");

        ChromeDriver driver = new ChromeDriver(options);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));

        logger.accept("써브 접속 중...");
        driver.get("https://www.serve.co.kr");

        if (isLoggedIn(driver)) {
            logger.accept("로그인 상태 유지 확인. 광고등록 페이지 이동 중...");
        } else {
            logger.accept("로그인 중...");
            performLogin(driver, wait);
        }

        // TODO: 광고 등록 페이지 이동 및 파일 첨부 구현
        // (써브 사이트 구조 확인 후 구현)
        logger.accept("광고등록 페이지 열림. 내용 확인 후 등록 버튼을 눌러주세요.");
    }

    private boolean isLoggedIn(WebDriver driver) {
        try {
            return !driver.findElements(By.cssSelector(".logout, .mypage, .user-info")).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void performLogin(ChromeDriver driver, WebDriverWait wait) throws Exception {
        // TODO: 써브 로그인 페이지 구조 확인 후 정확한 selector 입력
        wait.until(ExpectedConditions.presenceOfElementLocated(By.id("userId")));
        driver.findElement(By.id("userId")).sendKeys(id);
        driver.findElement(By.id("password")).sendKeys(password);
        driver.findElement(By.cssSelector("button[type=submit]")).click();
    }
}
