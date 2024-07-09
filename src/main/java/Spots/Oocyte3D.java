/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package SpotyDist;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.gui.WaitForUserDialog;
import ij.io.FileSaver;
import ij.plugin.Duplicator;
import ij.plugin.ImageCalculator;
import ij.plugin.RGBStackMerge;
import ij.process.ImageProcessor;
import static java.lang.Math.floor;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import mcib3d.geom.Object3D;
import mcib3d.geom.Object3D_IJUtils;
import mcib3d.geom.Objects3DPopulation;
import mcib3d.image3d.ImageHandler;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij2.CLIJ2;
import org.apache.commons.io.FilenameUtils;

/**
 *
 * @author gaelle
 */
public class Oocyte3D 
{
    private Object3D contour; // outer contour of the oocyte
    private int[] bbox;      // bounding box of oocyte position
    private Objects3DPopulation spots;  // found spots
    protected SpotyTools tools; // common informations/functions
    
    private String filename; // name of the file where it is extracted from
    private String ooname; // name of the file + id of the oocyte
    private String resdir; // folder where to save the results
    
    public Oocyte3D( Object3D obj, SpotyTools tool, String outdir )
    {
        contour = obj;
        tools = tool;
        resdir = outdir;
    }
    
    public void setName( String fname, int id )
    {
         filename = fname;
         ooname = filename+"_oocyte_"+id;
    }
    
    /**
     * Look for the spots in the oocyte
     */
    public void getSpots()
    {
        ImagePlus img = tools.openImage("spot", filename);
        tools.setImageCalibration(img);
        img.show();
        
        // Crop around oocyte
        bbox = contour.getBoundingBox();
        img.setRoi(bbox[0], bbox[2], bbox[1]-bbox[0], bbox[3]-bbox[2]);
        Duplicator dup = new Duplicator();
        contour.translate(-bbox[0], -bbox[2], -bbox[4]);  // update the contour to cropped window
        int minz = (int) floor(bbox[4]);
        if (minz==0){ minz = minz+1; }
        ImagePlus cropped = dup.run(img, minz, bbox[5]);
        IJ.run(cropped, "Subtract Background...", "rolling=20 stack");
        tools.setImageCalibration(cropped);
        cropped.show();
        tools.close(img);
        
        ImagePlus new_img = IJ.createImage(ooname, "8-bit black", cropped.getWidth(), cropped.getHeight(), cropped.getNSlices());
        tools.setImageCalibration(new_img);
        ImagePlus inside = drawOocyteContour(new_img, 255);
        tools.close(new_img);
        
        // get Dots
        spots = findDotsDoG(cropped, inside);
        
        IJ.log(spots.getNbObjects()+" dots found");
        saveOocyteImage( cropped );
    }
    
        /** 
     * Find dots with DOG method
     * @param img channel
     * @return dots population
     */
    public Objects3DPopulation findDotsDoG(ImagePlus img, ImagePlus inside) 
    {
        IJ.run(img, "32-bit", "");
        tools.removeImageCalibration(img);
        ImagePlus impDots = tools.DOG(img);
        impDots.show();
        IJ.resetMinAndMax(impDots);
        IJ.run(impDots, "Enhance Contrast", "saturated=0.35 process_all use");
        //WaitForUserDialog wait = new WaitForUserDialog("wait");
        //wait.show();
        
        IJ.run(impDots, "16-bit", "");
        IJ.run(impDots, "Select None", "");
        IJ.setAutoThreshold(impDots, tools.thMet+" dark stack");
        Prefs.blackBackground = true;
        IJ.run(impDots, "Convert to Mask", "method="+tools.thMet+" background=Dark stack");
        if (impDots.isInvertedLut()) IJ.run(impDots, "Invert LUT", "");
        Prefs.blackBackground = true;
        IJ.run(impDots, "Close-", "stack");
        IJ.run(inside, "Minimum...", "radius=10 stack");
        ImageCalculator ic = new ImageCalculator();
        ic.run("Min stack", impDots, inside);
        tools.close(inside);
        
        tools.setImageCalibration(impDots);
        ArrayList<Object3D> spots = (tools.getPopFromImage(impDots)).getObjectsWithinVolume(tools.minDots, tools.maxDots, true);
        Objects3DPopulation dotPop = new Objects3DPopulation(spots);
        tools.close(impDots);
        IJ.run(img, "16-bit", "");
        return(dotPop);
    } 
    
    
    /**
     * Measure the population of spots and the oocyte volume, distributions
     */
    public void doMeasurements()
    {
        // Open original image to measure intensities
        ImagePlus img = tools.openImage("spot", filename);
        tools.setImageCalibration(img);
        img.show();
        
        // Crop around oocyte
        img.setRoi(bbox[0], bbox[2], bbox[1]-bbox[0], bbox[3]-bbox[2]);
        Duplicator dup = new Duplicator();
        int minz = (int) floor(bbox[4]);
        if (minz==0){ minz = minz+1; }
        ImagePlus cropped = dup.run(img, minz, bbox[5]);
        tools.close(img);
        tools.setImageCalibration(cropped);
        
        ImageHandler ihand = ImageHandler.wrap(cropped);
        
        // Measure individual spots properties
        double spotsVolume = 0;
        double spotsSurface = 0;
        double spotsSphericity = 0;
        double dist = 0;
        int nspots = spots.getNbObjects();
        double[] vols = new double[nspots];
        double[] surfaces = new double[nspots];
        double[] sphericities = new double[nspots];
        double[] ints = new double[nspots];
        double[] dists = new double[nspots];
        double[] dneis = new double[nspots];
        double dneighbor = 0;
        double sumint = 0;
        double summeanint = 0;
        for (int i = 0; i < nspots; i++) 
        {
            IJ.showProgress(i, nspots);
            Object3D spot = spots.getObject(i);
            // spot intensity
            ints[i] = spot.getPixMeanValue(ihand);
            
            // object volume
            vols[i] = spot.getVolumeUnit();
            sphericities[i] = spot.getSphericity();
            surfaces[i] = spot.getAreaUnit();
            
            summeanint += ints[i];
            sumint += spot.getIntegratedDensity(ihand);
            spotsVolume += vols[i];
            spotsSurface += surfaces[i];
            spotsSphericity += sphericities[i];
            // distance to center
            dists[i] = spot.distCenterUnit(contour);
            dist += dists[i];
            // distance to neighbor
            Object3D nei = spots.closestCenter(spot, true);
            dneis[i] = nei.distCenterUnit(spot);
            dneighbor += dneis[i];
            
            // write individual stats
            tools.writeIndividualSpotStat( ooname, i, dists[i], vols[i], dneis[i], ints[i], surfaces[i], sphericities[i]);
         }
        
        try
        {
            double ooVolume = contour.getVolumeUnit();
            double ooTotInt = contour.getIntegratedDensity(ihand);
            
            tools.writeOocyteStat(ooname, ooVolume, nspots);
            tools.writeSpotsStat( nspots, vols, spotsVolume, dists, dist, dneis, dneighbor, ints, sumint, summeanint, ooTotInt, ooVolume, surfaces, spotsSurface, sphericities, spotsSphericity );
            tools.flushDistanceResults();
        }
        catch (Exception e)
        {
             Logger.getLogger(SpotyTools.class.getName()).log(Level.SEVERE, null, e);
        }
        tools.close(cropped);
    }
    
    /**
     * Create cells population image
     * @param cellPop
     * @param img
     * @param pathName

     */
    public void saveOocyteImage( ImagePlus img ) 
    {
        IJ.run(img, "Select None", "");
        
        ImagePlus new_img = IJ.createImage(ooname, "8-bit black", img.getWidth(), img.getHeight(), img.getNSlices());
        tools.setImageCalibration(new_img);
        ImagePlus img_contours = drawOocyteContour(new_img, 50);
        tools.close(new_img);
        
        ImagePlus new_img_spots = IJ.createImage(ooname, "8-bit black", img.getWidth(), img.getHeight(), img.getNSlices()); 
        tools.setImageCalibration(new_img_spots);
        ImageHandler imh = ImageHandler.wrap(new_img_spots);
        spots.draw(imh, 255);
        imh.show();
        ImagePlus img_spots = imh.getImagePlus();
        
        IJ.resetMinAndMax(img);
        IJ.run(img, "Enhance Contrast", "saturated=0.35");
        
        ImagePlus res = RGBStackMerge.mergeChannels(new ImagePlus[]{img_spots, img, img_contours}, false );
        res.show();
        tools.close(new_img_spots);        
        tools.close(img);
        tools.close(img_contours);
        //closeImages(img);
        
        tools.setImageCalibration(res);
        FileSaver resFile = new FileSaver(res);
        resFile.saveAsTiff(resdir+ooname+".tif");
        tools.close(res);
    }
    
    public ImagePlus drawOocyteContour( ImagePlus img, int val )
    {
        ImageHandler imh = ImageHandler.wrap(img);
        contour.draw(imh, val);
        ImagePlus contimg = imh.getImagePlus();
        return contimg;
    }
    
    
}
