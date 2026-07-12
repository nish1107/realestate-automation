package com.realestateauto.controller;

import com.realestateauto.config.AppConfig;
import com.realestateauto.service.BuildingService;
import com.realestateauto.service.LandRegisterService;
import com.realestateauto.service.RegistryService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class MainController {

    @FXML private TextField addressField;
    @FXML private TextField savePathField;
    @FXML private CheckBox registryCheck;
    @FXML private CheckBox buildingCheck;
    @FXML private CheckBox landCheck;
    @FXML private HBox registryTypeBox;
    @FXML private RadioButton malsoRadio;
    @FXML private RadioButton yuhyoRadio;
    @FXML private HBox addressTypeBox;
    @FXML private RadioButton roadNameRadio;
    @FXML private RadioButton jibunRadio;
    @FXML private HBox buildingSaveTypeBox;
    @FXML private CheckBox buildingManualCheck;
    @FXML private Button downloadBtn;
    @FXML private Button serveBtn;
    @FXML private TextArea logArea;

    @FXML private TextField irosIdField;
    @FXML private PasswordField irosPasswordField;
    @FXML private TextField irosPaymentAccountField;
    @FXML private PasswordField irosPaymentField;
    @FXML private TextField gov24IdField;
    @FXML private PasswordField gov24PasswordField;
    @FXML private TextField serveIdField;
    @FXML private PasswordField servePasswordField;

    private final AppConfig config = new AppConfig();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ToggleGroup registryTypeGroup = new ToggleGroup();
    private final ToggleGroup addressTypeGroup = new ToggleGroup();

    private static final DateTimeFormatter LOG_FILE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter LOG_LINE_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private File logDir;
    private BufferedWriter logWriter;

    private static final long WATCHDOG_TIMEOUT_MS = 5 * 60 * 1000L; // 5분
    private final AtomicLong lastLogTime = new AtomicLong(0);
    private final ScheduledExecutorService watchdogExecutor = Executors.newSingleThreadScheduledExecutor();
    private Future<?> currentTask;
    private ScheduledFuture<?> watchdogTask;

    @FXML
    public void initialize() {
        String initSavePath = config.get("savePath").isEmpty()
                ? System.getProperty("user.home") + "\\Downloads"
                : config.get("savePath");
        logDir = new File(initSavePath, "logs");
        logDir.mkdirs();
        String logFileName = LocalDateTime.now().format(LOG_FILE_FMT) + ".log";
        try {
            logWriter = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(new File(logDir, logFileName)), StandardCharsets.UTF_8));
        } catch (IOException ignored) {}

        irosIdField.setText(config.get("iros.id"));
        irosPasswordField.setText(config.get("iros.password"));
        irosPaymentAccountField.setText(config.get("iros.paymentAccount"));
        irosPaymentField.setText(config.get("iros.paymentPassword"));
        gov24IdField.setText(config.get("gov24.id"));
        gov24PasswordField.setText(config.get("gov24.password"));
        serveIdField.setText(config.get("serve.id"));
        servePasswordField.setText(config.get("serve.password"));
        savePathField.setText(config.get("savePath").isEmpty()
                ? System.getProperty("user.home") + "\\Downloads"
                : config.get("savePath"));

        malsoRadio.setToggleGroup(registryTypeGroup);
        yuhyoRadio.setToggleGroup(registryTypeGroup);
        if ("현재유효사항".equals(config.get("iros.recordType"))) {
            yuhyoRadio.setSelected(true);
        } else {
            malsoRadio.setSelected(true);
        }
        registryCheck.selectedProperty().addListener((obs, old, val) -> {
            registryTypeBox.setVisible(val);
            registryTypeBox.setManaged(val);
        });

        roadNameRadio.setToggleGroup(addressTypeGroup);
        jibunRadio.setToggleGroup(addressTypeGroup);
        roadNameRadio.setSelected(true);
        buildingCheck.selectedProperty().addListener((obs, old, val) -> {
            addressTypeBox.setVisible(val);
            addressTypeBox.setManaged(val);
            buildingSaveTypeBox.setVisible(val);
            buildingSaveTypeBox.setManaged(val);
        });
    }

    @FXML
    private void onSaveConfig() {
        config.set("iros.id", irosIdField.getText().trim());
        config.set("iros.password", irosPasswordField.getText());
        config.set("iros.paymentAccount", irosPaymentAccountField.getText().trim());
        config.set("iros.paymentPassword", irosPaymentField.getText());
        config.set("gov24.id", gov24IdField.getText().trim());
        config.set("gov24.password", gov24PasswordField.getText());
        config.set("iros.recordType", yuhyoRadio.isSelected() ? "현재유효사항" : "말소사항포함");
        config.set("gov24.addressType", jibunRadio.isSelected() ? "지번" : "도로명");
        config.set("serve.id", serveIdField.getText().trim());
        config.set("serve.password", servePasswordField.getText());
        config.set("savePath", savePathField.getText().trim());
        config.save();
        log("설정이 저장되었습니다.");
    }

    @FXML
    private void onChoosePath() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("저장 폴더 선택");
        File dir = chooser.showDialog(null);
        if (dir != null) {
            savePathField.setText(dir.getAbsolutePath());
        }
    }

    @FXML
    private void onDownload() {
        String address = addressField.getText().trim();
        if (address.isEmpty()) {
            showAlert("주소를 입력해주세요.");
            return;
        }
        if (!registryCheck.isSelected() && !buildingCheck.isSelected() && !landCheck.isSelected()) {
            showAlert("받을 서류를 하나 이상 선택해주세요.");
            return;
        }

        String savePath = savePathField.getText().trim();
        new File(savePath).mkdirs();

        setRunning(true);
        lastLogTime.set(System.currentTimeMillis());
        currentTask = executor.submit(() -> {
            try {
                if (registryCheck.isSelected()) {
                    String recordType = yuhyoRadio.isSelected() ? "현재유효사항" : "말소사항포함";
                    log("=== 등기부등본 다운로드 시작 [" + recordType + "] ===");
                    new RegistryService(config).download(address, savePath, recordType, this::log);
                }
                if (buildingCheck.isSelected()) {
                    String addrType = jibunRadio.isSelected() ? "지번" : "도로명";
                    config.set("gov24.addressType", addrType);
                    boolean manual = buildingManualCheck.isSelected();
                    log("=== 건축물대장 다운로드 시작 [" + addrType + (manual ? ", 직접저장" : ", 자동저장") + "] ===");
                    Runnable mbusterAlert = () -> {
                        CountDownLatch latch = new CountDownLatch(1);
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.WARNING,
                                    "보안 검증 페이지가 나타났습니다.\n브라우저에서 검증을 완료한 후 확인을 눌러주세요.\n\n확인 클릭 시 처음부터 자동으로 재시도합니다.",
                                    ButtonType.OK);
                            alert.setTitle("보안 검증 필요");
                            alert.setHeaderText(null);
                            alert.showAndWait();
                            latch.countDown();
                        });
                        try { latch.await(120, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    };
                    new BuildingService(config).download(address, savePath, manual, mbusterAlert, this::log);
                    if (manual) {
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                                    "건축물대장 뷰어가 열렸습니다.\n\n뷰어의 인쇄 버튼을 클릭하여 PDF로 저장해주세요.\n저장 완료 후 브라우저를 직접 닫아주세요.",
                                    ButtonType.OK);
                            alert.setTitle("직접저장 안내");
                            alert.setHeaderText(null);
                            alert.getDialogPane().setPrefWidth(380);
                            alert.showAndWait();
                        });
                    }
                }
                if (landCheck.isSelected()) {
                    log("=== 토지대장 다운로드 시작 ===");
                    new LandRegisterService(config).download(address, savePath, this::log);
                }
                log("=== 모든 다운로드 완료 ===");
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted() ||
                        e instanceof InterruptedException ||
                        (e.getCause() instanceof InterruptedException)) {
                    log("⚠ 작업이 타임아웃으로 강제 종료되었습니다.");
                } else {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    boolean windowClosed = msg.contains("target window already closed")
                            || msg.contains("no such window")
                            || msg.contains("web view not found")
                            || msg.contains("session deleted");
                    if (windowClosed) {
                        log("⚠ Chrome 창이 닫혀 작업을 중단합니다.");
                    } else {
                        log("오류 발생: " + msg);
                        logException(e);
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
                            alert.setTitle("다운로드 오류");
                            alert.setHeaderText(null);
                            alert.getDialogPane().setPrefWidth(420);
                            alert.showAndWait();
                        });
                    }
                }
            } finally {
                stopWatchdog();
                Platform.runLater(() -> setRunning(false));
            }
        });
        startWatchdog();
    }

    @FXML
    private void onOpenServe() {
        executor.submit(() -> {
            try {
                log("=== 써브 광고등록 페이지 열기 ===");
                com.realestateauto.automation.ServeAutomation serve =
                        new com.realestateauto.automation.ServeAutomation(
                                config.get("serve.id"),
                                config.get("serve.password"),
                                java.util.Collections.emptyList(),
                                this::log
                        );
                serve.openListingPage();
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log("오류 발생: " + msg);
                logException(e);
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
                    alert.setTitle("써브 오류");
                    alert.setHeaderText(null);
                    alert.showAndWait();
                });
            }
        });
    }

    private void log(String message) {
        lastLogTime.set(System.currentTimeMillis());
        String line = LocalTime.now().format(LOG_LINE_FMT) + " " + message;
        Platform.runLater(() -> {
            logArea.appendText(line + "\n");
            if (logWriter != null) {
                try {
                    logWriter.write(line);
                    logWriter.newLine();
                    logWriter.flush();
                } catch (IOException ignored) {}
            }
        });
    }

    private void startWatchdog() {
        watchdogTask = watchdogExecutor.scheduleAtFixedRate(() -> {
            long idleMs = System.currentTimeMillis() - lastLogTime.get();
            if (idleMs >= WATCHDOG_TIMEOUT_MS) {
                // 경보음 3회
                for (int i = 0; i < 3; i++) {
                    Toolkit.getDefaultToolkit().beep();
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
                log("⚠ [타임아웃] " + (idleMs / 1000 / 60) + "분간 응답 없음 - 작업을 강제 종료합니다.");
                stopWatchdog();
                if (currentTask != null) {
                    currentTask.cancel(true);
                }
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void stopWatchdog() {
        if (watchdogTask != null && !watchdogTask.isCancelled()) {
            watchdogTask.cancel(false);
        }
    }

    private void logException(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString();
        Platform.runLater(() -> {
            if (logWriter != null) {
                try {
                    logWriter.write("=== STACK TRACE ===");
                    logWriter.newLine();
                    logWriter.write(trace);
                    logWriter.write("===================");
                    logWriter.newLine();
                    logWriter.flush();
                } catch (IOException ignored) {}
            }
        });
    }

    @FXML
    private void onOpenLogFolder() {
        try {
            Desktop.getDesktop().open(logDir);
        } catch (Exception e) {
            showAlert("로그 폴더를 열 수 없습니다:\n" + logDir.getAbsolutePath());
        }
    }

    private void setRunning(boolean running) {
        downloadBtn.setDisable(running);
    }

    public void shutdown() {
        stopWatchdog();
        watchdogExecutor.shutdownNow();
        executor.shutdownNow();
        if (logWriter != null) {
            try { logWriter.close(); } catch (IOException ignored) {}
        }
        Platform.exit();
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
        alert.showAndWait();
    }
}
