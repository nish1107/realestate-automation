package com.realestateauto.automation;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

public class LandRegisterAutomation {

    private static final String GOV24_URL = "https://www.gov.kr";
    // 토지대장등본 발급 서비스 직접 URL
    private static final String LAND_SERVICE_URL =
        "https://www.gov.kr/mw/AA020InfoFRP.do?MWFM_ID=10600&GVRNMTS_ID=00&SVCID=SC000001093&fromMain=false";

    private final String id;
    private final String password;
    private final String savePath;
    private final Consumer<String> logger;

    public LandRegisterAutomation(String id, String password, String savePath, Consumer<String> logger) {
        this.id = id;
        this.password = password;
        this.savePath = savePath;
        this.logger = logger;
    }

    public void download(String address) throws Exception {
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--ignore-certificate-errors");
        options.addArguments("--allow-insecure-localhost");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--window-size=1280,900");
        options.addArguments("--disable-popup-blocking");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.setExperimentalOption("excludeSwitches", Arrays.asList("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);

        Map<String, Object> prefs = new HashMap<>();
        prefs.put("download.default_directory", new File(savePath).getAbsolutePath());
        prefs.put("download.prompt_for_download", false);
        prefs.put("download.directory_upgrade", true);
        prefs.put("plugins.always_open_pdf_externally", true);
        options.setExperimentalOption("prefs", prefs);

        String profileDir = System.getProperty("user.home") + "/gov24-chrome-profile";
        new File(profileDir).mkdirs();
        options.addArguments("--user-data-dir=" + profileDir);

        ChromeDriver driver = new ChromeDriver(options);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));

        try {
            logger.accept("정부24 접속 중...");
            driver.get(GOV24_URL);
            waitForText(driver, 8000, "로그인", "로그아웃", "민원서비스");

            // 점검 중 여부 확인
            String initialText = getPageText(driver);
            if (initialText.contains("점검") && !initialText.contains("로그인")) {
                throw new RuntimeException("정부24 서비스 점검 중입니다.\n잠시 후 다시 시도해주세요.");
            }

            // 로그인 필요 시 자동 로그인 시도
            if (!isLoggedIn(driver)) {
                if (!id.isEmpty() && !password.isEmpty()) {
                    logger.accept("정부24 로그인 시도 중...");
                    tryLogin(driver);
                }
                if (!isLoggedIn(driver)) {
                    logger.accept("━━━━━━━━━━━━━━━━━━━━━━━━");
                    logger.accept("정부24 자동 로그인 실패 - 수동 로그인 필요");
                    logger.accept("열린 창에서 직접 로그인해주세요. (최대 5분 대기)");
                    logger.accept("※ 정부24는 간편인증(카카오, 네이버 등) 권장");
                    logger.accept("━━━━━━━━━━━━━━━━━━━━━━━━");
                    waitForManualLogin(driver);
                }
                logger.accept("로그인 확인됨.");
            } else {
                logger.accept("로그인 상태 확인됨.");
            }

            // 토지대장 서비스로 이동
            logger.accept("토지대장 발급 서비스 이동 중...");
            navigateToLandService(driver);

            // 주소로 토지 검색
            logger.accept("주소 검색: " + address);
            searchAndDownload(driver, address);

        } finally {
            try { driver.quit(); } catch (Exception ignored) {}
        }
    }

    private void tryLogin(ChromeDriver driver) throws InterruptedException {
        // 정부24 로그인 페이지로 이동
        driver.get(GOV24_URL + "/portal/login");
        waitForText(driver, 5000, "아이디", "비밀번호", "로그인");

        // ID 입력
        String idResult = (String) ((JavascriptExecutor) driver).executeScript(
            "var inputs = document.querySelectorAll('input[type=text], input:not([type])');" +
            "for (var i = 0; i < inputs.length; i++) {" +
            "  var el = inputs[i];" +
            "  if (!el.offsetParent) continue;" +
            "  var id2 = (el.id || '').toLowerCase();" +
            "  var ph = (el.placeholder || '').toLowerCase();" +
            "  if (id2.includes('id') || id2.includes('userid') || ph.includes('아이디') || ph.includes('id')) {" +
            "    el.value = arguments[0];" +
            "    el.dispatchEvent(new Event('input', {bubbles:true}));" +
            "    el.dispatchEvent(new Event('change', {bubbles:true}));" +
            "    return '아이디입력:' + el.id;" +
            "  }" +
            "}" +
            "return 'notfound';",
            id);
        logger.accept("아이디 입력: " + idResult);
        Thread.sleep(300);

        // 비밀번호 입력
        String pwResult = (String) ((JavascriptExecutor) driver).executeScript(
            "var pws = document.querySelectorAll('input[type=password]');" +
            "for (var i = 0; i < pws.length; i++) {" +
            "  if (!pws[i].offsetParent) continue;" +
            "  pws[i].value = arguments[0];" +
            "  pws[i].dispatchEvent(new Event('input', {bubbles:true}));" +
            "  pws[i].dispatchEvent(new Event('change', {bubbles:true}));" +
            "  return '비밀번호입력:' + pws[i].value.length + '자';" +
            "}" +
            "return 'notfound';",
            password);
        logger.accept("비밀번호 입력: " + pwResult);
        Thread.sleep(300);

        // 로그인 버튼 클릭
        String loginClick = (String) ((JavascriptExecutor) driver).executeScript(
            "var btns = document.querySelectorAll('button, a, input[type=submit], input[type=button]');" +
            "for (var i = 0; i < btns.length; i++) {" +
            "  var el = btns[i];" +
            "  if (!el.offsetParent) continue;" +
            "  var t = (el.innerText || el.value || '').trim();" +
            "  if (t === '로그인' || t === '로그인하기') {" +
            "    el.click();" +
            "    return '로그인버튼클릭:' + t;" +
            "  }" +
            "}" +
            "return 'notfound';");
        logger.accept("로그인 클릭: " + loginClick);

        // 로그인 완료 대기
        waitForText(driver, 8000, "로그아웃", "마이페이지");
    }

    private void navigateToLandService(ChromeDriver driver) throws InterruptedException {
        // 토지대장 서비스 직접 접근 시도
        driver.get(LAND_SERVICE_URL);
        Thread.sleep(2000);

        String pageText = getPageText(driver);
        // 서비스 URL 접근 실패 시 검색으로 찾기
        if (!pageText.contains("토지대장") && !pageText.contains("지번") && !pageText.contains("필지")) {
            logger.accept("서비스 검색으로 재시도...");
            driver.get(GOV24_URL);
            Thread.sleep(1000);

            // 검색창에 "토지대장" 입력
            Boolean searched = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "var inputs = document.querySelectorAll('input[type=search], input[type=text]');" +
                "for (var i = 0; i < inputs.length; i++) {" +
                "  var el = inputs[i];" +
                "  if (!el.offsetParent) continue;" +
                "  var ph = (el.placeholder || '').trim();" +
                "  if (ph.includes('검색') || el.name === 'searchKeyword' || el.id.includes('search')) {" +
                "    el.value = '토지대장등본';" +
                "    el.dispatchEvent(new Event('input', {bubbles:true}));" +
                "    var form = el.closest('form');" +
                "    if (form) { form.submit(); return true; }" +
                "    el.dispatchEvent(new KeyboardEvent('keypress', {key:'Enter', keyCode:13, bubbles:true}));" +
                "    return true;" +
                "  }" +
                "}" +
                "return false;");

            if (Boolean.TRUE.equals(searched)) {
                waitForText(driver, 5000, "토지대장", "발급");
                // 검색 결과에서 토지대장 클릭
                ((JavascriptExecutor) driver).executeScript(
                    "var links = document.querySelectorAll('a');" +
                    "for (var i = 0; i < links.length; i++) {" +
                    "  var t = (links[i].innerText || '').trim();" +
                    "  if (t.includes('토지대장') && links[i].offsetParent) {" +
                    "    links[i].click(); return t;" +
                    "  }" +
                    "}");
                Thread.sleep(2000);
            }
        }

        pageText = getPageText(driver);
        if (!pageText.contains("토지대장") && !pageText.contains("지번")) {
            throw new RuntimeException("토지대장 서비스 페이지를 찾지 못했습니다.\n정부24(www.gov.kr)에서 '토지대장' 서비스를 수동으로 확인해주세요.");
        }
        logger.accept("토지대장 서비스 페이지 로드 완료");
    }

    private void searchAndDownload(ChromeDriver driver, String address) throws InterruptedException {
        // 발급하기 버튼 클릭
        Thread.sleep(1000);
        Boolean applyClicked = (Boolean) ((JavascriptExecutor) driver).executeScript(
            "var btns = document.querySelectorAll('a, button');" +
            "for (var i = 0; i < btns.length; i++) {" +
            "  var t = (btns[i].innerText || '').trim();" +
            "  if ((t === '발급하기' || t === '신청하기' || t === '민원신청') && btns[i].offsetParent) {" +
            "    btns[i].click(); return true;" +
            "  }" +
            "}" +
            "return false;");

        if (Boolean.TRUE.equals(applyClicked)) {
            logger.accept("발급 신청 페이지 진입...");
            Thread.sleep(2000);
            waitForText(driver, 10000, "주소", "지번", "번지", "시도");
        }

        String pageText = getPageText(driver);
        logger.accept("현재 페이지: " + pageText.substring(0, Math.min(100, pageText.length())).replaceAll("\\s+", " "));

        // 주소 검색 버튼 또는 입력 필드 찾기
        Boolean addressSearchClicked = (Boolean) ((JavascriptExecutor) driver).executeScript(
            "var btns = document.querySelectorAll('a, button');" +
            "for (var i = 0; i < btns.length; i++) {" +
            "  var t = (btns[i].innerText || '').trim();" +
            "  if ((t.includes('주소') && t.includes('검색')) || t === '주소찾기' || t === '지번검색') {" +
            "    if (btns[i].offsetParent) { btns[i].click(); return true; }" +
            "  }" +
            "}" +
            "return false;");

        if (Boolean.TRUE.equals(addressSearchClicked)) {
            logger.accept("주소 검색 팝업 열림...");
            Thread.sleep(1500);

            // 팝업 창으로 전환
            String mainHandle = driver.getWindowHandle();
            Set<String> handles = driver.getWindowHandles();
            for (String handle : handles) {
                if (!handle.equals(mainHandle)) {
                    driver.switchTo().window(handle);
                    break;
                }
            }

            // 주소 입력 및 검색
            String addressToSearch = extractSimpleAddress(address);
            ((JavascriptExecutor) driver).executeScript(
                "var inputs = document.querySelectorAll('input[type=text], input:not([type])');" +
                "for (var i = 0; i < inputs.length; i++) {" +
                "  if (inputs[i].offsetParent) {" +
                "    inputs[i].value = arguments[0];" +
                "    inputs[i].dispatchEvent(new Event('input', {bubbles:true}));" +
                "    var form = inputs[i].closest('form');" +
                "    if (form) form.submit();" +
                "    else inputs[i].dispatchEvent(new KeyboardEvent('keypress', {key:'Enter', keyCode:13, bubbles:true}));" +
                "    return true;" +
                "  }" +
                "}" +
                "return false;",
                addressToSearch);

            Thread.sleep(2000);
            waitForText(driver, 8000, "번지", "선택", "지번");

            // 첫 번째 결과 선택
            ((JavascriptExecutor) driver).executeScript(
                "var links = document.querySelectorAll('a, button, tr');" +
                "for (var i = 0; i < links.length; i++) {" +
                "  var t = (links[i].innerText || '').trim();" +
                "  if (t && links[i].offsetParent && (t.includes('번지') || t.includes('리'))) {" +
                "    links[i].click(); return t;" +
                "  }" +
                "}");

            Thread.sleep(1000);
            // 메인 창으로 복귀
            driver.switchTo().window(mainHandle);
        }

        Thread.sleep(1000);
        pageText = getPageText(driver);
        logger.accept("주소 설정 후 페이지 상태 확인 중...");

        // 신청 완료 버튼 클릭 (민원 신청)
        Boolean submitted = (Boolean) ((JavascriptExecutor) driver).executeScript(
            "var btns = document.querySelectorAll('button, input[type=submit], a');" +
            "for (var i = 0; i < btns.length; i++) {" +
            "  var t = (btns[i].innerText || btns[i].value || '').trim();" +
            "  if ((t === '신청하기' || t === '발급' || t === '민원신청' || t === '확인') && btns[i].offsetParent) {" +
            "    btns[i].click(); return '클릭:' + t;" +
            "  }" +
            "}" +
            "return 'notfound';");
        logger.accept("신청 버튼: " + submitted);

        Thread.sleep(3000);

        // 다운로드 또는 PDF 버튼 찾기
        waitForDownloadOrPdf(driver);
    }

    private void waitForDownloadOrPdf(ChromeDriver driver) throws InterruptedException {
        long end = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < end) {
            Boolean found = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "var btns = document.querySelectorAll('a, button');" +
                "for (var i = 0; i < btns.length; i++) {" +
                "  var t = (btns[i].innerText || '').trim();" +
                "  if ((t.includes('다운') || t.includes('PDF') || t.includes('출력') || t === '저장') && btns[i].offsetParent) {" +
                "    btns[i].click(); return true;" +
                "  }" +
                "}" +
                "return false;");
            if (Boolean.TRUE.equals(found)) {
                logger.accept("다운로드 시작됨.");
                Thread.sleep(3000);
                return;
            }
            Thread.sleep(1000);
        }
        logger.accept("다운로드 버튼을 자동으로 찾지 못했습니다. 브라우저에서 직접 저장해주세요.");
    }

    private String extractSimpleAddress(String address) {
        // "탑실로 152 203동 102호" → "탑실로 152" (동/호 제거)
        String result = address.trim()
            .replaceAll("\\s*\\d+동\\s*\\d+호.*$", "")
            .replaceAll("\\s*\\d+호.*$", "")
            .trim();
        return result.isEmpty() ? address : result;
    }

    private boolean isLoggedIn(WebDriver driver) {
        try {
            String text = (String) ((JavascriptExecutor) driver)
                .executeScript("return document.body.innerText || ''");
            return text != null && (text.contains("로그아웃") || text.contains("마이페이지"));
        } catch (Exception e) {
            return false;
        }
    }

    private void waitForManualLogin(ChromeDriver driver) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            Thread.sleep(5000);
            if (isLoggedIn(driver)) return;
        }
        throw new RuntimeException("로그인 대기 시간 초과 (5분)");
    }

    private void waitForText(ChromeDriver driver, int maxMs, String... texts) throws InterruptedException {
        long end = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < end) {
            try {
                String pageText = getPageText(driver);
                for (String t : texts) {
                    if (pageText.contains(t)) return;
                }
            } catch (Exception ignored) {}
            Thread.sleep(400);
        }
    }

    private String getPageText(WebDriver driver) {
        try {
            return (String) ((JavascriptExecutor) driver)
                .executeScript("return document.body.innerText || ''");
        } catch (Exception e) {
            return "";
        }
    }
}
