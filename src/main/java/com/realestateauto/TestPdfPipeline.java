package com.realestateauto;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * canvas → 다페이지 PDF 파이프라인 자체 테스트.
 * 실제 Gov24 사이트 없이 파이프라인의 정확성을 검증한다.
 *
 * 테스트 흐름:
 *  1. Chrome 실행
 *  2. about:blank에서 2개 canvas 생성 (각각 A4 가로 크기, 구분 가능한 내용)
 *  3. canvas.toDataURL()로 JPEG 데이터 추출
 *  4. 새 탭에 A4 landscape HTML 구성
 *  5. Page.printToPDF로 PDF 생성
 *  6. 출력 파일 검증 (페이지 수, 파일 크기, PDF 시그니처)
 */
public class TestPdfPipeline {

    private static final String OUT_PATH = System.getProperty("user.home") + "\\Desktop\\test_pipeline_output.pdf";

    public static void main(String[] args) throws Exception {
        System.out.println("=== PDF 파이프라인 자체 테스트 시작 ===");
        System.out.println("출력: " + OUT_PATH);

        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        // 실제 환경과 동일하게 headless 사용 안 함
        WebDriver driver = new ChromeDriver(options);

        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;

            // --- 1단계: canvas 2개 생성 ---
            driver.get("about:blank");
            Thread.sleep(500);

            js.executeScript(
                "document.body.style.margin='0';" +
                "document.body.style.background='white';" +
                // canvas 1
                "var c1=document.createElement('canvas');" +
                "c1.width=1587; c1.height=1123;" + // A4 landscape @ 135 DPI
                "c1.id='page1';" +
                "var ctx1=c1.getContext('2d');" +
                "ctx1.fillStyle='white'; ctx1.fillRect(0,0,1587,1123);" +
                "ctx1.fillStyle='#000'; ctx1.font='bold 60px serif';" +
                "ctx1.fillText('테스트 1페이지',80,150);" +
                "ctx1.font='36px serif';" +
                "ctx1.fillText('건축물대장 자동화 파이프라인 검증',80,250);" +
                "ctx1.fillText('canvas → JPEG → A4 Landscape PDF',80,320);" +
                "ctx1.strokeStyle='#333'; ctx1.lineWidth=4;" +
                "ctx1.strokeRect(40,40,1507,1043);" +
                "document.body.appendChild(c1);" +
                // canvas 2
                "var c2=document.createElement('canvas');" +
                "c2.width=1587; c2.height=1123;" +
                "c2.id='page2';" +
                "var ctx2=c2.getContext('2d');" +
                "ctx2.fillStyle='#f0f8ff'; ctx2.fillRect(0,0,1587,1123);" +
                "ctx2.fillStyle='#000'; ctx2.font='bold 60px serif';" +
                "ctx2.fillText('테스트 2페이지',80,150);" +
                "ctx2.font='36px serif';" +
                "ctx2.fillText('이 페이지가 존재하면 다페이지 PDF 성공',80,250);" +
                "ctx2.fillText('Page 2 of 2',80,320);" +
                "ctx2.strokeStyle='#00f'; ctx2.lineWidth=4;" +
                "ctx2.strokeRect(40,40,1507,1043);" +
                "document.body.appendChild(c2);"
            );

            // --- 2단계: canvas 데이터 추출 ---
            System.out.println("[1] canvas 데이터 추출 중...");
            String canvasJson = (String) js.executeScript(
                "var canvases = document.querySelectorAll('canvas');" +
                "var result = [];" +
                "for (var i = 0; i < canvases.length; i++) {" +
                "  var c = canvases[i];" +
                "  result.push({data: c.toDataURL('image/jpeg',0.92), w:c.width, h:c.height});" +
                "}" +
                "return JSON.stringify(result);"
            );

            if (canvasJson == null || !canvasJson.startsWith("[")) {
                throw new RuntimeException("canvas 데이터 추출 실패: " + canvasJson);
            }

            int pageCount = countOccurrences(canvasJson, "\"data\"");
            System.out.println("[1] 추출된 페이지 수: " + pageCount);
            if (pageCount != 2) throw new RuntimeException("예상 2페이지, 실제: " + pageCount);

            // --- 3단계: 새 탭에 A4 HTML 구성 ---
            System.out.println("[2] A4 Landscape HTML 탭 생성 중...");
            String mainHandle = driver.getWindowHandle();
            js.executeScript("window.open('about:blank','_pdftest');");
            Thread.sleep(500);

            String buildHandle = null;
            for (String h : driver.getWindowHandles()) {
                if (!h.equals(mainHandle)) { buildHandle = h; break; }
            }
            if (buildHandle == null) throw new RuntimeException("새 탭 핸들 없음");
            driver.switchTo().window(buildHandle);

            // HTML 삽입: 각 canvas 이미지가 정확히 A4 landscape 1페이지
            js.executeScript(
                "var data = " + canvasJson + ";" +
                "var html = '<html><head><style>" +
                "*{margin:0;padding:0;box-sizing:border-box}" +
                "body{background:white}" +
                ".pg{width:297mm;height:210mm;page-break-after:always;" +
                "page-break-inside:avoid;overflow:hidden;display:flex;" +
                "align-items:center;justify-content:center;background:white}" +
                ".pg img{width:100%;height:100%;object-fit:fill}" +
                "@page{size:A4 landscape;margin:0}" +
                "</style></head><body>';" +
                "for(var i=0;i<data.length;i++){" +
                "  html+='<div class=\\'pg\\'><img src=\\''+data[i].data+'\\'/></div>';" +
                "}" +
                "html+='</body></html>';" +
                "document.open();document.write(html);document.close();"
            );
            Thread.sleep(1000);

            // --- 4단계: Page.printToPDF ---
            System.out.println("[3] Page.printToPDF 실행 중...");
            Map<String, Object> pdfParams = new HashMap<>();
            pdfParams.put("printBackground", true);
            pdfParams.put("preferCSSPageSize", true);
            pdfParams.put("landscape", true);
            pdfParams.put("paperWidth", 11.69);   // 297mm in inches
            pdfParams.put("paperHeight", 8.27);   // 210mm in inches
            pdfParams.put("marginTop", 0.0);
            pdfParams.put("marginBottom", 0.0);
            pdfParams.put("marginLeft", 0.0);
            pdfParams.put("marginRight", 0.0);

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>)
                ((org.openqa.selenium.chrome.ChromeDriver) driver).executeCdpCommand("Page.printToPDF", pdfParams);
            String b64 = (String) result.get("data");
            if (b64 == null || b64.isEmpty()) throw new RuntimeException("printToPDF 결과 없음");

            byte[] pdfBytes = java.util.Base64.getDecoder().decode(b64);

            // --- 5단계: 저장 및 검증 ---
            try (FileOutputStream fos = new FileOutputStream(OUT_PATH)) {
                fos.write(pdfBytes);
            }
            System.out.println("[4] 저장 완료: " + OUT_PATH);
            System.out.println("[4] 파일 크기: " + pdfBytes.length + " bytes");

            // PDF 시그니처 확인
            if (pdfBytes[0] != '%' || pdfBytes[1] != 'P' || pdfBytes[2] != 'D' || pdfBytes[3] != 'F') {
                throw new RuntimeException("PDF 시그니처 오류: " + new String(pdfBytes, 0, 4, StandardCharsets.ISO_8859_1));
            }
            System.out.println("[4] PDF 시그니처 OK (%PDF)");

            // 페이지 수 추정 (PDF 내 /Type /Page 카운트)
            String pdfStr = new String(pdfBytes, StandardCharsets.ISO_8859_1);
            int pdfPageCount = countOccurrences(pdfStr, "/Type /Page") + countOccurrences(pdfStr, "/Type/Page");
            System.out.println("[4] PDF 내 페이지 수 추정: " + pdfPageCount);

            if (pdfBytes.length < 50_000) {
                System.out.println("[경고] 파일이 너무 작음 (" + pdfBytes.length + " bytes) - 내용 확인 필요");
            }
            if (pdfPageCount < 2) {
                System.out.println("[경고] 2페이지 미만으로 감지됨 - 실제 PDF 뷰어로 확인 필요");
            }

            // 탭 닫기
            driver.close();
            driver.switchTo().window(mainHandle);

            System.out.println("");
            System.out.println("=== 테스트 결과 ===");
            System.out.println("파일: " + OUT_PATH);
            System.out.println("크기: " + pdfBytes.length + " bytes (" + (pdfBytes.length / 1024) + " KB)");
            System.out.println("페이지(추정): " + pdfPageCount);
            if (pdfPageCount >= 2 && pdfBytes.length > 50_000) {
                System.out.println(">> 파이프라인 검증 성공 - 실제 사이트 적용 가능");
            } else {
                System.out.println(">> 추가 확인 필요 - 위 경고 확인");
            }

        } finally {
            Thread.sleep(2000);
            driver.quit();
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(needle, idx)) != -1) { count++; idx += needle.length(); }
        return count;
    }
}
