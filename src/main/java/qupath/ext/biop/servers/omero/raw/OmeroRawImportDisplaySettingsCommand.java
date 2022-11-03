package qupath.ext.biop.servers.omero.raw;

import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.servers.ImageServer;

import java.awt.image.BufferedImage;

public class OmeroRawImportDisplaySettingsCommand implements Runnable{

    private final String title = "Import view settings from OMERO";
    private QuPathGUI qupath;
    public OmeroRawImportDisplaySettingsCommand(QuPathGUI qupath)  {
        this.qupath = qupath;
    }

    @Override
    public void run() {

        // get the current image
        ImageServer<BufferedImage> imageServer = this.qupath.getViewer().getServer();

        // Check if OMERO server
        if (!(imageServer instanceof OmeroRawImageServer)) {
            Dialogs.showErrorMessage(title, "The current image is not from OMERO!");
            return;
        }

        // build the GUI for view settings options
        GridPane pane = new GridPane();

        CheckBox cbChannelNames = new CheckBox("Channel names");
        cbChannelNames.setSelected(false);

        CheckBox cbChannelDisplayRange = new CheckBox("Channel display ranges");
        cbChannelDisplayRange.setSelected(false);

        CheckBox cbChannelColor = new CheckBox("Channel colors");
        cbChannelColor.setSelected(false);

        int row = 0;
        pane.add(new Label("Select view settings options"), 0, row++, 2, 1);
        pane.add(cbChannelNames, 0, row++);
        pane.add(cbChannelDisplayRange, 0, row++);
        pane.add(cbChannelColor, 0, row);

        pane.setHgap(5);
        pane.setVgap(5);

        if (!Dialogs.showConfirmDialog(title, pane))
            return;

        // get user choice
        boolean channelNames = cbChannelNames.isSelected();
        boolean channelDisplayRange = cbChannelDisplayRange.isSelected();
        boolean channelColor = cbChannelColor.isSelected();

        // set OMERO display settings on QuPath image
        if(channelDisplayRange)
            OmeroRawScripting.setDisplayRange((OmeroRawImageServer)imageServer);
        if(channelColor)
            OmeroRawScripting.setChannelColorFromOmeroChannel((OmeroRawImageServer)imageServer);
        if(channelNames)
            OmeroRawScripting.setChannelNameFromOmeroChannel((OmeroRawImageServer)imageServer);

        Dialogs.showInfoNotification("View settings import","View settings successfully set the current image");
    }

}
