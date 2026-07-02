package com.realestateauto;

import com.realestateauto.controller.MainController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("main.fxml"));
        Scene scene = new Scene(loader.load(), 500, 550);
        MainController controller = loader.getController();
        stage.setTitle("부동산 서류 자동 다운로더");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.setOnCloseRequest(e -> controller.shutdown());
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
