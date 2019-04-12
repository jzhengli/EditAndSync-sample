/*
 * Copyright 2018 Esri.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.arcgis.developers.labs;

import com.esri.arcgisruntime.concurrent.Job;
import com.esri.arcgisruntime.concurrent.ListenableFuture;
import com.esri.arcgisruntime.data.*;
import com.esri.arcgisruntime.geometry.Envelope;
import com.esri.arcgisruntime.geometry.Point;
import com.esri.arcgisruntime.geometry.SpatialReferences;
import com.esri.arcgisruntime.layers.ArcGISMapImageLayer;
import com.esri.arcgisruntime.layers.ArcGISTiledLayer;
import com.esri.arcgisruntime.layers.FeatureLayer;
import com.esri.arcgisruntime.loadable.LoadStatus;
import com.esri.arcgisruntime.mapping.GeoElement;
import com.esri.arcgisruntime.mapping.Viewpoint;
import com.esri.arcgisruntime.mapping.view.IdentifyLayerResult;
import com.esri.arcgisruntime.portal.Portal;
import com.esri.arcgisruntime.security.AuthenticationManager;
import com.esri.arcgisruntime.security.DefaultAuthenticationChallengeHandler;
import com.esri.arcgisruntime.security.OAuthConfiguration;
import com.esri.arcgisruntime.tasks.geodatabase.*;
import com.esri.arcgisruntime.tasks.tilecache.ExportTileCacheJob;
import com.esri.arcgisruntime.tasks.tilecache.ExportTileCacheParameters;
import com.esri.arcgisruntime.tasks.tilecache.ExportTileCacheTask;
import com.oracle.xmlns.internal.webservices.jaxws_databinding.SoapBindingParameterStyle;
import com.sun.jndi.toolkit.url.Uri;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import com.esri.arcgisruntime.mapping.ArcGISMap;
import com.esri.arcgisruntime.mapping.Basemap;
import com.esri.arcgisruntime.mapping.view.MapView;

import java.io.File;
import java.net.MalformedURLException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class App extends Application {

    //create variables
    private MapView mapView;
    private String lyrURL = "https://services.arcgis.com/Wl7Y1m92PbjtJs5n/arcgis/rest/services/Fire_Stations_Island_of_Hawaii/FeatureServer";
    private String gdbPath = System.getProperty("user.dir") + "\\hawaii.geodatabase";
    private GeodatabaseSyncTask gdbSyncTask = new GeodatabaseSyncTask(lyrURL);

    //method for setting up authentication
    private void setupAuthentication() {
//        System.out.println("gdbPath: " + gdbPath);
        String portalURL = "https://www.arcgis.com";
        String clientId = "FQp1zqewCv8cdyMF";
        String redirectURI = "urn:ietf:wg:oauth:2.0:oob";

        try {
            OAuthConfiguration oAuthConfiguration = new OAuthConfiguration(portalURL, clientId, redirectURI);
            AuthenticationManager.setAuthenticationChallengeHandler(new DefaultAuthenticationChallengeHandler());
            AuthenticationManager.addOAuthConfiguration(oAuthConfiguration);

            final Portal portal = new Portal(portalURL, true);
            portal.addDoneLoadingListener(() -> {
                if (portal.getLoadStatus() == LoadStatus.LOADED) {
                    File gdbFile = new File(gdbPath);
//                    System.out.println(gdbFile.getAbsolutePath());
                    if (!gdbFile.exists()) {
                        System.out.println("GDB doesn't exist, creating from feature layer...");
                        createMobileGDB();
                    } else {
                        System.out.println("GDB exists.");
                    }
                    //call editFeature method to perform edits - provide it with geodatabase and feature table name
                    editFeature(gdbFile, "Fire_Stations_Island_of_Hawaii");
                } else {
                    new Alert(Alert.AlertType.ERROR, "Portal: " + portal.getLoadError().getMessage()).show();
                }
            });
            portal.loadAsync();
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
    }

    //method for creating offline geodatabase on local disk from service online
    private void createMobileGDB() {
//        gdbSyncTask = new GeodatabaseSyncTask(lyrURL);

        // define an extent for the features to include
        Envelope extent = mapView.getCurrentViewpoint(Viewpoint.Type.BOUNDING_GEOMETRY).getTargetGeometry().getExtent();

        // get the default parameters for generating a geodatabase
        ListenableFuture<GenerateGeodatabaseParameters> generateGdbParamsFuture =
                gdbSyncTask.createDefaultGenerateGeodatabaseParametersAsync(extent);
        generateGdbParamsFuture.addDoneListener(() -> {
            try {
                GenerateGeodatabaseParameters generateGdbParams = generateGdbParamsFuture.get();
                // each layer within geodatabase can be synchronized independently of one another
                generateGdbParams.setSyncModel(SyncModel.PER_LAYER);
                // define the layers and features to include
                int fireRiskArea = 0;
                // Clear and re-create the layer options
                generateGdbParams.getLayerOptions().clear();
                generateGdbParams.getLayerOptions().add(new GenerateLayerOption(fireRiskArea));
                // do not return attachments
                generateGdbParams.setReturnAttachments(false);
                File gdbFile = new File("hawaii.geodatabase");

                // create the generate geodatabase job, pass in the parameters and an output path for the local geodatabase
                GenerateGeodatabaseJob generateGdbJob =
                        gdbSyncTask.generateGeodatabase(generateGdbParams, gdbFile.getAbsolutePath());

                // handle the JobChanged event to check the status of the job
                generateGdbJob.addJobChangedListener(() -> {
                    System.out.println("Job Status: " + generateGdbJob.getStatus().name());
                    if (generateGdbJob.getError() != null) {
                        System.out.println("Error Message: " + generateGdbJob.getError().getMessage());
                    }
                });

                generateGdbJob.addJobDoneListener(() -> {
                    if ((generateGdbJob.getStatus() != Job.Status.SUCCEEDED) || (generateGdbJob.getError() != null)) {
                        System.out.println("Job Error: " + generateGdbJob.getError());
                    } else {
                        displayMessage("GDB Creation Result", "GDB has been created successfully!");
                        editFeature(gdbFile, "Fire_Stations_Island_of_Hawaii");
                    }
                });

                // start the job and display job id
                generateGdbJob.start();
                System.out.println("Submitted job #" + generateGdbJob.getServerJobId() + " to create local geodatabase");
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    //method for editing feature - update geometry location
    private void editFeature(File gdbFile, String featureTableName) {
        Geodatabase gdb = new Geodatabase(gdbFile.getAbsolutePath());
        gdb.addDoneLoadingListener(() -> {
            if (gdb.getLoadStatus() == LoadStatus.LOADED) {
                GeodatabaseFeatureTable gdbTable = gdb.getGeodatabaseFeatureTable(featureTableName);
//                gdbTable.loadAsync();

                //display the data on map
                FeatureLayer featureLayer = new FeatureLayer(gdbTable);
                mapView.getMap().getOperationalLayers().add(featureLayer);
                System.out.println("FeatureTable loaded");

                //response to mouse click event
                mapView.setOnMouseClicked((MouseEvent event) -> {
                    if (event.isStillSincePress() && event.getButton() == MouseButton.PRIMARY) {
                        //get the x, y coordinates for mouse-clicked location on the map
                        Point2D point = new Point2D(event.getX(), event.getY());
                        Point mapPoint = mapView.screenToLocation(point);

                        //identify any clicked feature
                        ListenableFuture<IdentifyLayerResult> results = mapView
                                .identifyLayerAsync(featureLayer, point, 1, false, 1);
                        results.addDoneListener(() -> {
                            try {
                                //get selected feature
                                List<GeoElement> elements = results.get().getElements();
                                if (elements.size() > 0 && elements.get(0) instanceof ArcGISFeature) {
                                    //clicked on a feature, select it
                                    ArcGISFeature selected = (ArcGISFeature) elements.get(0);
                                    featureLayer.clearSelection();
                                    featureLayer.selectFeature(selected);
//                                    System.out.println("Feature selected by clicking");
                                } else {
                                    //didn't click on a feature
                                    ListenableFuture<FeatureQueryResult> selectedQuery = featureLayer.getSelectedFeaturesAsync();
                                    selectedQuery.addDoneListener(() -> {
                                        try {
                                            //check if a feature is currently selected
                                            FeatureQueryResult selectedQueryResult = selectedQuery.get();
                                            Iterator<Feature> features = selectedQueryResult.iterator();
                                            if ((features).hasNext()) {
//                                                System.out.println("a feature is currently selected");
                                                //move selected feature to clicked location
                                                ArcGISFeature selected = (ArcGISFeature) features.next();
                                                selected.loadAsync();
                                                selected.addDoneLoadingListener(() -> {
//                                                    System.out.println(selected.canUpdateGeometry());
                                                    if (selected.canUpdateGeometry()) {
                                                        //set the geometry of selected feature to the new location
                                                        selected.setGeometry(mapPoint);
                                                        //update changes to the geodatabase
                                                        ListenableFuture<Void> featureTableResult = gdbTable.updateFeatureAsync(selected);
                                                        //call syncEdits method to synchronize changes to the service online
                                                        featureTableResult.addDoneListener(() -> syncEdits(gdb));
                                                    }
                                                });
                                            } //else do nothing
                                        } catch (InterruptedException | ExecutionException e) {
                                            displayMessage("Exception getting selected feature", e.getCause().getMessage());
                                        }
                                    });
                                }
                            } catch (InterruptedException | ExecutionException e) {
                                displayMessage("Exception getting clicked feature", e.getCause().getMessage());
                            }
                        });
                    } else if (event.isStillSincePress() && event.getButton() == MouseButton.SECONDARY) {
                        featureLayer.clearSelection();
                    }
                });
            }
        });
        gdb.loadAsync();
    }

    //method for synchronizing edits back to service online
    private void syncEdits(Geodatabase gdb) {
        ListenableFuture<SyncGeodatabaseParameters> syncParamsFuture = gdbSyncTask
                .createDefaultSyncGeodatabaseParametersAsync(gdb);
        syncParamsFuture.addDoneListener(() -> {
            try {
                //get and set sync parameters for sync job
                SyncGeodatabaseParameters syncParams = syncParamsFuture.get();
                SyncGeodatabaseJob syncGdbJob =
                        gdbSyncTask.syncGeodatabase(syncParams, gdb);

                //monitor job changes
                syncGdbJob.addJobChangedListener(() -> {
                    if (syncGdbJob.getError() != null) {
                        System.out.println("Job Error: " + syncGdbJob.getError());
                    }
                });

                //when job is done, inform user that sync is successful
                syncGdbJob.addJobDoneListener(() -> {
                    if ((syncGdbJob.getStatus() != Job.Status.SUCCEEDED) || (syncGdbJob.getError() != null)) {
                        System.out.println("Job Error: " + syncGdbJob.getError());
                    } else {
                        List<SyncLayerResult> syncResults = (List<SyncLayerResult>) syncGdbJob.getResult();
                        if (syncResults != null) {
                            displayMessage("Sync Result", "Successfully synchronized to service online.");
                        }
                    }
                });

                syncGdbJob.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    //help method for displaying message
    private void displayMessage(String title, String message) {
        Platform.runLater(() -> {
            Alert dialog = new Alert(Alert.AlertType.INFORMATION);
            dialog.initOwner(mapView.getScene().getWindow());
            dialog.setHeaderText(title);
            dialog.setContentText(message);
            dialog.showAndWait();
        });
    }

    //set up the map
    private void setupMap() {
        if (mapView != null) {
            File tpkFile = new File("HIBasemap.tpk");
            ArcGISTiledLayer localTileLayer = new ArcGISTiledLayer(tpkFile.getPath());
            ArcGISMap map = new ArcGISMap(new Basemap(localTileLayer));
            mapView.setMap(map);
        }
    }

    @Override
    public void start(Stage stage) {
        StackPane stackPane = new StackPane();
        Scene scene = new Scene(stackPane);

        stage.setTitle("Training Project 1");
        stage.setWidth(800);
        stage.setHeight(550);
        stage.setScene(scene);
        stage.show();

        mapView = new MapView();

        setupMap();
        setupAuthentication();
//    addFeatureLayer();
        stackPane.getChildren().add(mapView);
    }

    @Override
    public void stop() {
        if (mapView != null) {
            mapView.dispose();
        }
    }

    public static void main(String[] args) {
        Application.launch(args);
    }

}