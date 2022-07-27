/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2018 - 2021 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.biop.servers.omero.raw;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import org.apache.commons.lang3.StringUtils;

import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.tools.PaneTools;
import qupath.lib.objects.PathObject;

/**
 * Command to write path objects back to the OMERO server where the
 * current image is hosted.
 *
 * @author Melvin Gelbard
 *
 */
public class OmeroRawWriteDetectionObjectsCommand implements Runnable {

    private final String title = "Send objects to OMERO";

    private QuPathGUI qupath;

    OmeroRawWriteDetectionObjectsCommand(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    @Override
    public void run() {
        var viewer = qupath.getViewer();
        var server = viewer.getServer();

        // Check if OMERO server
        if (!(server instanceof OmeroRawImageServer)) {
            Dialogs.showErrorMessage(title, "The current image is not from OMERO!");
            return;
        }

        // Check if at least one object was selected (and type)
        var selectedObjects = viewer.getAllSelectedObjects();
        Collection<PathObject> objs;
        if (selectedObjects.size() == 0) {
            // If no selection, get all detection objects
            objs = viewer.getHierarchy().getDetectionObjects();
            if (objs.size() == 0) {
                Dialogs.showErrorMessage(title, "No detections to send!");
                return;
            }

            // Ask user if he/she wants to send all detections instead
            var confirm = Dialogs.showConfirmDialog("Send detections", String.format("No detections are selected. Send all detections instead? (%d %s)",
                    objs.size(),
                    (objs.size() == 1 ? "object" : "objects")));

            if (!confirm)
                return;
        } else {
            objs = selectedObjects;

            // Get annotations among the selection
            var annotations = objs.stream().filter(e -> e.isAnnotation()).collect(Collectors.toList());

            // Give warning and filter out annotation objects
            if (annotations.size() > 0) {
                Dialogs.showWarningNotification(title, String.format("Selected annotations will not be imported on OMERO (%d %s)",
                        annotations.size(),
                        (annotations.size() == 1 ? "object" : "objects")));

                objs = objs.stream().filter(e -> !e.isAnnotation()).collect(Collectors.toList());
            }

            // Output message if no detection object was found
            if (objs.size() == 0) {
                Dialogs.showErrorMessage(title, "No detection objects to send!");
                return;
            }
        }

        // Confirm
        var omeroServer = (OmeroRawImageServer) server;
        URI uri = server.getURIs().iterator().next();
        String objectString = "object" + (objs.size() == 1 ? "" : "s");
        GridPane pane = new GridPane();
        PaneTools.addGridRow(pane, 0, 0, null, new Label(String.format("%d %s will be sent to:", objs.size(), objectString)));
        PaneTools.addGridRow(pane, 1, 0, null, new Label(uri.toString()));
        var confirm = Dialogs.showConfirmDialog("Send " + (selectedObjects.size() == 0 ? "all " : "") + objectString, pane);
        if (!confirm)
            return;

        // Write path object(s)
        try {
            OmeroRawTools.writePathObjects(objs, omeroServer);
            Dialogs.showInfoNotification(StringUtils.capitalize(objectString) + " written successfully", String.format("%d %s %s successfully written to OMERO server",
                    objs.size(),
                    objectString,
                    (objs.size() == 1 ? "was" : "were")));
        } catch (IOException ex) {
            Dialogs.showErrorNotification("Could not send " + objectString, ex.getLocalizedMessage());
        } catch (ExecutionException | DSOutOfServiceException | DSAccessException e) {
            throw new RuntimeException(e);
        }
    }
}