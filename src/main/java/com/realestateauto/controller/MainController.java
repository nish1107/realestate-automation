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

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainController {

    @FXML private TextField addressField;
    @FXML private TextField savePathField;
    @FXML private CheckBox registryCheck;
    @FXML private CheckBox buildingCheck;
    @FXML private CheckBox landCheck;
    @FXML private HBox addressTypeBox;
    @FXML private RadioButton roadNameRadio;
    @FXML private RadioButton jibunRadio;
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
    private final ToggleGroup addressTypeGroup = new ToggleGroup();

    @FXML
    public void initialize() {
        irosIdField.setText(config.get("iros.id"));
        irosPasswordField.setText(config.get("iros.password"));
        irosPaymentAccountField.setText(config.get("iros.paymentAccount"));
        irosPaymentField.setText(config.get("iros.paymentPassword"));
        gov24IdField.setText(config.get("gov24.id"));
        gov24PasswordField.setText(config.get("gov24.password"));
        serveIdField.setText(config.get("serve.id"));
        servePasswordField.setText(config.get("serve.password"));
        savePathField.setText(config.get("savePath").isEmpty()
                ? System.getProperty("user.home") + "\\Desktop\\부동산서류"
                : config.get("savePath"));

        roadNameRadio.setToggleGroup(addressTypeGroup);
        jibunRadio.setToggleGroup(addressTypeGroup);
        if ("지번".equals(config.get("gov24.addressType"))) {
            jibunRadio.setSelected(true);
        } else {
            roadNameRadio.setSelected(true);
        }
        buildingCheck.selectedProperty().addListener((obs, old, val) -> {
            addressTypeBox.setVisible(val);
            addressTypeBox.setManaged(val);
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
        executor.submit(() -> {
            try {
                if (registryCheck.isSelected()) {
                    log("=== 등기부등본 다운로드 시작 ===");
                    new RegistryService(config).download(address, savePath, this::log);
                }
                if (buildingCheck.isSelected()) {
                    String addrType = jibunRadio.isSelected() ? "지번" : "도로명";
                    config.set("gov24.addressType", addrType);
                    log("=== 건축물대장 다운로드 시작 [" + addrType + "] ===");
                    new BuildingService(config).download(address, savePath, this::log);
                }
                if (landCheck.isSelected()) {
                    log("=== 토지대장 다운로드 시작 ===");
                    new LandRegisterService(config).download(address, savePath, this::log);
                }
                log("=== 모든 다운로드 완료 ===");
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                log("오류 발생: " + msg);
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
                    alert.setTitle("다운로드 오류");
                    alert.setHeaderText(null);
                    alert.getDialogPane().setPrefWidth(420);
                    alert.showAndWait();
                });
            } finally {
                Platform.runLater(() -> setRunning(false));
            }
        });
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
                log("오류 발생: " + e.getMessage());
            }
        });
    }

    private void log(String message) {
        Platform.runLater(() -> logArea.appendText(message + "\n"));
    }

    private void setRunning(boolean running) {
        downloadBtn.setDisable(running);
        serveBtn.setDisable(running);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
        alert.showAndWait();
    }
}
