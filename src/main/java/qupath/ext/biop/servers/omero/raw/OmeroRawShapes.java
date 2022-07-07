package qupath.ext.biop.servers.omero.raw;

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

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.gson.*;
import omero.model.Roi;
import org.locationtech.jts.geom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.annotations.SerializedName;

import qupath.lib.geom.Point2;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.*;
import qupath.lib.roi.interfaces.ROI;

class OmeroRawShapes {

    private final static Logger logger = LoggerFactory.getLogger(OmeroRawShapes.class);


    public static Roi convertQuPathRoiToOmeroRoi(PathObject src) {
        ROI roi = src.getROI();

        OmeroRawShape shape;
        if (roi instanceof RectangleROI) {
            shape = new Rectangle(roi.getBoundsX(), roi.getBoundsY(), roi.getBoundsWidth(), roi.getBoundsHeight(), roi.getC(), roi.getZ(), roi.getT());

        } else if (roi instanceof EllipseROI) {
            shape = new Ellipse(roi.getCentroidX(), roi.getCentroidY(), roi.getBoundsWidth()/2, roi.getBoundsHeight()/2, roi.getC(), roi.getZ(), roi.getT());

        } else if (roi instanceof LineROI) {
            LineROI lineRoi = (LineROI)roi;
            shape = new Line(lineRoi.getX1(), lineRoi.getY1(), lineRoi.getX2(), lineRoi.getY2(), roi.getC(), roi.getZ(), roi.getT());

        } else if (roi instanceof PolylineROI) {
            shape = new Polyline(pointsToString(roi.getAllPoints()), roi.getC(), roi.getZ(), roi.getT());

        } else if (roi instanceof PolygonROI) {
            shape = new Polygon(pointsToString(roi.getAllPoints()), roi.getC(), roi.getZ(), roi.getT());

        } else if (roi instanceof PointsROI) {
            //JsonElement[] points = new JsonElement[roi.getNumPoints()];
            List<Point2> roiPoints = roi.getAllPoints();
            PathClass pathClass = src.getPathClass();

            for (int i = 0; i < roiPoints.size(); i++) {
                shape = new Point(roiPoints.get(i).getX(), roiPoints.get(i).getY(), roi.getC(), roi.getZ(), roi.getT());
                shape.setText(src.getName() != null ? src.getName() : "");
                shape.setFillColor(pathClass != null ? ARGBToRGBA(src.getPathClass().getColor()) : -256);
                //points[i] = context.serialize(shape, Point.class);
            }
            return null;//context.serialize(points);

        } else if (roi instanceof GeometryROI) {
            // MultiPolygon
            //logger.info("OMERO shapes do not support holes.");
            logger.warn("MultiPolygon will be split for OMERO compatibility.");

            //roi = RoiTools.fillHoles(roi);
            PathClass pathClass = src.getPathClass();
            List<ROI> rois = RoiTools.splitROI(roi);

            /* TODO try to find a way to recongnize shapes (rectangle, ellipse) instead of instancing by default polygon
            TODO find a way to write complex roi in OMERo => May be review the polygonROI deserializer to take into account internal shapes
            rois.forEach(e->System.out.println("ROIname : "+e.getRoiName()));
            */

            rois = splitHolesAndShape(rois);

           // JsonElement[] polygons = new JsonElement[rois.size()];

            for (int i = 0; i < rois.size(); i++) {
                shape = new Polygon(pointsToString(rois.get(i).getAllPoints()), roi.getC(), roi.getZ(), roi.getT());
                shape.setText(src.getName() != null ? src.getName() : "");
                shape.setFillColor(pathClass != null ? ARGBToRGBA(pathClass.getColor()) : -256);
               // polygons[i] = context.serialize(shape, Polygon.class);
            }

            return null;//context.serialize(polygons);

        } else {
            logger.warn("Unsupported type {}", roi.getRoiName());
            return null;
        }

        // Set the appropriate colors
        if (src.getPathClass() != null) {
            int classColor = ARGBToRGBA(src.getPathClass().getColor());
            shape.setFillColor(classColor);
            shape.setStrokeColor(classColor);
        } else {
            shape.setFillColor(-256);	// Transparent
            shape.setStrokeColor(ARGBToRGBA(PathPrefs.colorDefaultObjectsProperty().get())); // Default Qupath object color
        }

        shape.setText(src.getName() != null ? src.getName() : "");
        return null;//context.serialize(shape, type);
    }


    /**
     * Split holes and envelop of the same polygon into independent ROIs.
     *
     * @param rois List of split ROIs
     * @return list of ROIs of decoupling external and internal rings
     */
    private static List<ROI> splitHolesAndShape(List<ROI> rois) {
        List<ROI> polygonROIs = new ArrayList<>();
        for (int i = 0; i < rois.size(); i++){
            // Only process shapes that are polygons
            if (rois.get(i).getGeometry() instanceof org.locationtech.jts.geom.Polygon) {
                // get the external shape of the polygon
                org.locationtech.jts.geom.Polygon polygon = (org.locationtech.jts.geom.Polygon) rois.get(i).getGeometry();
                Coordinate[] polygonCoordinates = polygon.getExteriorRing().getCoordinates();
                List<Point2> polygonROICoordinates = new ArrayList<>();

                for (int j = 0; j < polygonCoordinates.length; j++){
                    polygonROICoordinates.add(new Point2(polygonCoordinates[j].x, polygonCoordinates[j].y));
                }

                polygonROIs.add(ROIs.createPolygonROI(polygonROICoordinates, rois.get(i).getImagePlane()));

                // get the internal shapes of the polygon (i.e. holes)
                for (int j = 0; j < polygon.getNumInteriorRing(); j++) {
                    polygonCoordinates = polygon.getInteriorRingN(j).getCoordinates();
                    polygonROICoordinates = new ArrayList<>();

                    for (int k = 0; k < polygonCoordinates.length; k++) {
                        polygonROICoordinates.add(new Point2(polygonCoordinates[k].x, polygonCoordinates[k].y));
                    }

                    polygonROIs.add(ROIs.createPolygonROI(polygonROICoordinates, rois.get(i).getImagePlane()));
                }
            }
            else{
                polygonROIs.add(rois.get(i));
            }
        }

        return polygonROIs;
    }

    /**
     * Return the packed RGBA representation of the specified ARGB (packed) value.
     * <p>
     * This doesn't use the convenient method {@code makeRGBA()} as
     * the order in the method is confusing.
     * @param argb
     * @return rgba
     */
    private static int ARGBToRGBA(int argb) {
        int a =  (argb >> 24) & 0xff;
        int r =  (argb >> 16) & 0xff;
        int g =  (argb >> 8) & 0xff;
        int b =  argb & 0xff;
        return (r<<24) + (g<<16) + (b<<8) + a;
    }

//	/**
//	 * Return the packed ARGB representation of the specified RGBA (packed) value.
//	 * <p>
//	 * This method is similar to {@code makeRGBA()} but with packed RGBA input.
//	 * @param rgba
//	 * @return argb
//	 */
//	private static int RGBAToARGB(int rgba) {
//		int r =  (rgba >> 24) & 0xff;
//		int g =  (rgba >> 16) & 0xff;
//		int b =  (rgba >> 8) & 0xff;
//		int a =  rgba & 0xff;
//		return (a<<24) + (r<<16) + (g<<8) + b;
//	}

    public static abstract class OmeroRawShape {
        private int c = -1;
        private int z;
        private int t;
        private String type;
        private String text;
        private Boolean locked;
        private Integer fillColor;
        private Integer strokeColor;

        private String oldId = "-1:-1";

        private void setC(int c){ this.c = c;}
        private void setZ(int z){  this.z = z;}
        private void setT(int t){ this.t = t;}


        private PathObject createObject(Function<ROI, PathObject> fun) {
            var pathObject = fun.apply(createROI());
            initializeObject(pathObject);
            return pathObject;
        }

        abstract ROI createROI();

        protected PathObject createAnnotation() {
            return createObject(PathObjects::createAnnotationObject);
        }

        protected PathObject createDetection() {
            return createObject(PathObjects::createDetectionObject);
        }

        protected void initializeObject(PathObject pathObject) {
            if (text != null && !text.isBlank())
                pathObject.setName(text);
            if (strokeColor != null)
                pathObject.setColorRGB(strokeColor >> 8);
            if (locked != null)
                pathObject.setLocked(locked);
        }


        protected ImagePlane getPlane() {
            if (c >= 0)
                return ImagePlane.getPlaneWithChannel(c, z, t);
            else
                return ImagePlane.getPlane(z, t);
        }

        protected void setType(String type) {
            this.type = "http://www.openmicroscopy.org/Schemas/OME/2016-06#" + type;
        }

        protected void setText(String text) {
            this.text = text;
        }

        protected void setStrokeColor(Integer color) {
            this.strokeColor = color;
        }

        protected void setFillColor(Integer color) {
            this.fillColor = color;
        }

    }

    static class Rectangle extends OmeroRawShapes.OmeroRawShape {

        private double x;
        private double y;
        private double width;
        private double height;

        private Rectangle(double x, double y, double width, double height, int c, int z, int t) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;

            super.setType("Rectangle");
            super.setC(c);
            super.setZ(z);
            super.setT(t);

        }

        @Override
        ROI createROI() {
            logger.debug("Creating rectangle");
            return ROIs.createRectangleROI(x, y, width, height, getPlane());
        }
    }

    static class Ellipse extends OmeroRawShapes.OmeroRawShape {


        private double x;

        private double y;

        private double radiusX;

        private double radiusY;

        private Ellipse(double x, double y, double radiusX, double radiusY, int c, int z, int t) {
            this.x = x;
            this.y = y;
            this.radiusX = radiusX;
            this.radiusY = radiusY;

            super.setType("Ellipse");
            super.setC(c);
            super.setZ(z);
            super.setT(t);
        }

        @Override
        ROI createROI() {
            logger.debug("Creating ellipse");
            return ROIs.createEllipseROI(x-radiusX, y-radiusY, radiusX*2, radiusY*2, getPlane());
        }
    }

    static class Line extends OmeroRawShapes.OmeroRawShape {

        private double x1;
        private double y1;
        private double x2;
        private double y2;

        private Line(double x1, double y1, double x2, double y2, int c, int z, int t) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;

            super.setType("Line");
            super.setC(c);
            super.setZ(z);
            super.setT(t);
        }

        @Override
        ROI createROI() {
            logger.debug("Creating line");
            return ROIs.createLineROI(x1, y1, x2, y2, getPlane());
        }
    }

    static class Point extends OmeroRawShapes.OmeroRawShape {
        private double x;
        private double y;

        private Point(double x, double y, int c, int z, int t) {
            this.x = x;
            this.y = y;

            super.setType("Point");
            super.setC(c);
            super.setZ(z);
            super.setT(t);
        }

        @Override
        ROI createROI() {
            logger.debug("Creating point");
            return ROIs.createPointsROI(x, y, getPlane());
        }
    }

    static class Polyline extends OmeroRawShapes.OmeroRawShape {

        private String pointString;

        private Polyline(String pointString, int c, int z, int t) {
            this.pointString = pointString;

            super.setType("Polyline");
            super.setC(c);
            super.setZ(z);
            super.setT(t);
        }

        @Override
        ROI createROI() {
            logger.debug("Creating polyline");
            return ROIs.createPolylineROI(parseStringPoints(pointString), getPlane());
        }
    }

    static class Polygon extends OmeroRawShapes.OmeroRawShape {

        private String pointString;

        private Polygon(String pointString, int c, int z, int t) {
            this.pointString = pointString;

            super.setType("Polygon");
            super.setC(c);
            super.setZ(z);
            super.setT(t);
        }

        @Override
        ROI createROI() {
            logger.debug("Creating polygon");
            return ROIs.createPolygonROI(parseStringPoints(pointString), getPlane());
        }
    }

    static class Label extends OmeroRawShapes.OmeroRawShape {

        private double x;
        private double y;

        private Label(double x, double y, int c, int z, int t) {
            this.x = x;
            this.y = y;

            super.setType("Label");
            super.setC(c);
            super.setZ(z);
            super.setT(t);
        }

        @Override
        ROI createROI() {
            logger.warn("Creating point (requested label shape is unsupported)");
            return ROIs.createPointsROI(x, y, getPlane());
        }
    }

    static class Mask extends OmeroRawShapes.OmeroRawShape {

        @Override
        ROI createROI() {
            throw new UnsupportedOperationException("Mask rois not yet supported!");
        }
    }

    /**
     * Parse the OMERO string representing points
     * @param pointsString
     * @return list of Point2
     */
    private static List<Point2> parseStringPoints(String pointsString) {
        List<Point2> points = new ArrayList<>();
        for (String p : pointsString.split(" ")) {
            String[] p2 = p.split(",");
            points.add(new Point2(Double.parseDouble(p2[0]), Double.parseDouble(p2[1])));
        }
        return points;
    }

    /**
     * Converts the specified list of {@code Point2}s into an OMERO-friendly string
     * @param points
     * @return string of points
     */
    private static String pointsToString(List<Point2> points) {
        return points.stream().map(e -> e.getX() + "," + e.getY()).collect(Collectors.joining (" "));


    }

}