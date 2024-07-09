package SpotyDist;


import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.WaitForUserDialog;
import ij.measure.Calibration;
import ij.plugin.ImageCalculator;
import ij.plugin.frame.RoiManager;
import ij.process.AutoThresholder;
import java.awt.Color;
import java.awt.Font;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.ImageIcon;
import loci.formats.meta.IMetadata;
import mcib3d.geom.Object3D;
import mcib3d.geom.Objects3DPopulation;
import mcib3d.image3d.ImageHandler;
import mcib3d.image3d.ImageInt;
import mcib3d.image3d.ImageLabeller;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij2.CLIJ2;
import org.apache.commons.io.FilenameUtils;
import org.scijava.util.ArrayUtils;

        
 /*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose dots_Tools | Templates
 * and open the template in the editor.
 */

/**
 *
 * @author gletort
 */
public class SpotyTools 
{
   
    private String spot_chanel_name = ""; // name of the chanel in which spots signal is
    private String dna_chanel_name = ""; // name of the chanel in which dna signal is
    private String imageDir = ""; // main directory of the images
    
    private RoiManager rm;
    
    // min volume in µm^3 for cells
    public double minDots = 0.5;
    // max volume in µm^3 for cells
    public double maxDots = 1000;
    // Dog parameters
    private double sigma1 = 1;
    private double sigma2 = 4;
    //private double sigmaz = 1;
    private double aspect_ratio = 1;  // aspect ratio between xy and z calibration
    
    private Calibration cal = new Calibration(); 

    //private double radiusNei = 50; //neighboring radius
    //private int nbNei = 10; // K neighborg
        
    private BufferedWriter outPutResults;
    private BufferedWriter outPutDistances;
    
    // dots threshold method
    protected String thMet = "MaxEntropy";
     
    public CLIJ2 clij2 = CLIJ2.getInstance();
    public final ImageIcon icon = new ImageIcon(this.getClass().getResource("/logoAI.png"));
    
     /**
     * check  installed modules
     * @return 
     */
    public boolean checkInstalledModules() {
        // check install
        ClassLoader loader = IJ.getClassLoader();
        try {
            loader.loadClass("net.haesleinhuepf.clij2.CLIJ2");
        } catch (ClassNotFoundException e) {
            IJ.log("CLIJ not installed, please install from update site");
            return false;
        }
        try {
            loader.loadClass("mcib3d.geom.Object3D");
        } catch (ClassNotFoundException e) {
            IJ.log("3D ImageJ Suite not installed, please install from update site");
            return false;
        }
        
        return true;
    }
    
    /**
     * Find images in folder
     */
    public ArrayList<String> findImages(String imagesFolder, String imageExt) {
        File inDir = new File(imagesFolder);
        imageDir = imagesFolder;
        String[] files = inDir.list();
        if (files == null) {
            System.out.println("No Image found in "+imagesFolder);
            return null;
        }
        ArrayList<String> images = new ArrayList();
        for (String f : files) {
            // Find images with extension
            String fileExt = FilenameUtils.getExtension(f);
            if (fileExt.equals(imageExt))
                images.add(imagesFolder + File.separator + f);
        }
        Collections.sort(images);
        return(images);
    }
    
    /**
     * Extract the name of the file without the chanel name
     * @param fname
     * @return 
     */
    public String getPureName( String fname )
    {
        String filename = FilenameUtils.getBaseName(fname);
        String purename = filename.replace(spot_chanel_name, "");
        return purename;
    }
    
    /**
     * Open the chose image (spot or dna)
     * @param what
     * @return 
     */
    public ImagePlus openImage( String what, String filename )
    {
        if ( what.equals("spot") )
            return IJ.openImage(imageDir+filename+spot_chanel_name+".TIF");
        if ( what.equals("dna") )
            return IJ.openImage(imageDir+filename+dna_chanel_name+".TIF");
        return null;
    }
    
      /**
     * Find image type
     */
    public String findImageType(File imagesFolder) 
    {
        String ext = "";
        String[] files = imagesFolder.list();
        for (String name : files) {
            String fileExt = FilenameUtils.getExtension(name);
            switch (fileExt) {
                case "nd" :
                    return fileExt;
                case "czi" :
                  return fileExt;
                case "lif"  :
                    return fileExt;
                case "isc2" :
                   return fileExt;
                default :
                   ext = fileExt;
                   break; 
            }
        }
        return(ext);
    }
    
        /** Close without saving */
	public void close(ImagePlus ip)
	{ 
		ip.changes = false;
		ip.close();
	}

        /** \brief Be sure nothing is selected in the image */
        public void unselectImage(ImagePlus ip)
        {
            	IJ.run(ip, "Select None", "");
		IJ.run("Select None");
		IJ.run("Set Measurements...", "area centroid perimeter bounding stack redirect=None decimal=2");
		IJ.run(ip, "Remove Overlay", "");
	
        }
        
     /**
     * Empyt ROIManager
     */
    public void reset_manager()
    {       
        if (rm.getCount()>0)
        {
           rm.runCommand("Deselect");
           rm.runCommand("Delete");
        }     
    }
        
        /**
         * Open the roiManager if not opened yet
         */
        public void initRoiManager()
        {
            rm = RoiManager.getInstance();
            if ( rm == null )
                rm = new RoiManager();
        }
    /**
     * Read and set image calibration
     */
    public void setCalibration(IMetadata meta) 
    {
        // read image calibration
        cal = new Calibration();
        cal.pixelWidth = meta.getPixelsPhysicalSizeX(0).value().doubleValue();
        cal.pixelHeight = cal.pixelWidth;
        if (meta.getPixelsPhysicalSizeZ(0) != null)
            cal.pixelDepth = meta.getPixelsPhysicalSizeZ(0).value().doubleValue();
        else
            cal.pixelDepth = 1;
        cal.setUnit("microns");
        //System.out.println("XY calibration = " + cal.pixelWidth + ", Z calibration = " + cal.pixelDepth);
    }
    
    public void setImageCalibration(ImagePlus imp)
    {
        imp.setCalibration(cal);
    }
    
    public void removeImageCalibration(ImagePlus img)
    {
        Calibration cali = new Calibration();
        cali.pixelWidth = 1;
        cali.pixelDepth = 1;
        img.setCalibration(cali);
    }
    
    public Calibration getCalib()
    {
        System.out.println("x cal = " +cal.pixelWidth+", z cal=" + cal.pixelDepth);
        return cal;
    }
    
    public double getSD(double[] tab, double mean){
        double res = 0;
        for (int i=0; i<tab.length; i++){
            res += (tab[i]-mean)*(tab[i]-mean);
        }
        return Math.sqrt(res/tab.length);
    }
    
    /**
     * Applyt the thresold method at each slice
     * @param imp
     * @param method 
     */
    public void apply_threshold( ImagePlus imp, String method )
    {
        Prefs.blackBackground = true;
        IJ.run(imp, "Convert to Mask", "method="+method+" background=Dark calculate black");
    }
    
    /**
     *
     * @param img
     */
    public void closeImages(ImagePlus img) {
        img.flush();
        img.close();
    }
    
    public Objects3DPopulation getPopFromImage(ImagePlus img) 
    {
        // label binary images first
        ImageLabeller labeller = new ImageLabeller();
        ImageInt labels = labeller.getLabels(ImageHandler.wrap(img));
        Objects3DPopulation pop = new Objects3DPopulation(labels);
        return pop;
    }
      
    public ArrayList<Object3D> getObjectsFromImage(ImagePlus img, double minVol, double maxVol) 
    {
        Objects3DPopulation pop = getPopFromImage(img);
        return pop.getObjectsWithinVolume(minVol, maxVol, true);
    }
    
    public ImagePlus apply_median_filter( ImagePlus img, double rad )
    {
        double radxy = rad; // radius of the filter in pixels
        double radz = rad * cal.pixelWidth/cal.pixelDepth;
        ClearCLBuffer imgCL = clij2.push(img);
        ClearCLBuffer imgCLDOG = median_filter(imgCL, radxy, radz);
        clij2.release(imgCL);
        ImagePlus impFiltered = clij2.pull(imgCLDOG);
        clij2.release(imgCLDOG);
        return impFiltered;
    }
    
    
    /* Median filter 
     * Using CLIJ2
     * @param ClearCLBuffer
     * @param sizeXY
     * @param sizeZ
     */ 
    public ClearCLBuffer median_filter(ClearCLBuffer  imgCL, double sizeXY, double sizeZ) {
        ClearCLBuffer imgCLMed = clij2.create(imgCL);
        clij2.median3DBox(imgCL, imgCLMed, sizeXY, sizeXY, sizeZ);
        clij2.release(imgCL);
        return(imgCLMed);
    }
    
    
    /**
     * Difference of Gaussians 
     * Using CLIJ2
     * @param imgCL
     * @param size1
     * @param size2
     * @return imgGauss
     */ 
    public ImagePlus DOG(ImagePlus img) 
    {
        ClearCLBuffer imgCLDOG = median_filter(clij2.push(img),1,1);
        ClearCLBuffer imgCLDOGmed = median_filter(clij2.push(img),1,1);
        //ClearCLBuffer imgCLDOGmed = median_filter(imgCL, 1, 1);
        clij2.differenceOfGaussian3D(imgCLDOG, imgCLDOGmed, sigma1, sigma1, sigma1, sigma2, sigma2, sigma2);
        clij2.release(imgCLDOG);
        //clij2.release(imgCL);
        ImagePlus impDots = clij2.pull(imgCLDOGmed);
        clij2.release(imgCLDOG);
        return impDots;
    }
    

   
   
    
    /**
     * Dialog 
     * 
     * @param channels
     * @return 
     */
    public void dialog(String[] channels) 
    {
        GenericDialogPlus gd = new GenericDialogPlus("Parameters");
        gd.setInsets​(0, 80, 0);
        gd.addImage(icon);
        gd.addMessage("Channels selection", Font.getFont("Monospace"), Color.blue);
        int index = 0;
        //String[] chNames = {"Spots", "DNA"};
        String[] chNames = {"Spots"}; 
        for (String chName : chNames) 
        {
            gd.addChoice(chNames[index]+" : ", channels, channels[index]);
            index++;
        }
        gd.addMessage("Dots detection parameters", Font.getFont("Monospace"), Color.blue);
        gd.addNumericField("Min dots size (µm3) : ", minDots, 3);
        gd.addNumericField("Max dots size (µm3) : ", maxDots, 3);
        String[] methods = AutoThresholder.getMethods();         
        gd.addChoice("Thresholding method :", methods, thMet);
        gd.addMessage("Difference of Gaussian (radius1 < radius2)", Font.getFont("Monospace"), Color.blue);
        gd.addNumericField("radius 1 (pixels) : ", sigma1, 2);
        gd.addNumericField("radius 2 (pixels) : ", sigma2, 2);
        //gd.addNumericField("radius z (pixels) : ", sigmaz, 2);
        gd.addMessage("Image calibration", Font.getFont("Monospace"), Color.blue);
        gd.addNumericField("Calibration xy (µm)  :", cal.pixelWidth, 4);
        gd.addNumericField("Calibration z (µm)  :", cal.pixelDepth, 4);
        //gd.addMessage("Spatial distribution", Font.getFont("Monospace"), Color.blue);
        //gd.addNumericField("Radius for neighboring analysis : ", radiusNei, 2);
        //gd.addNumericField("Number of neighborgs : ", nbNei, 2);
        
        gd.showDialog();
        
        spot_chanel_name = gd.getNextChoice();
        //dna_chanel_name = gd.getNextChoice();
        
        minDots = gd.getNextNumber();
        maxDots = gd.getNextNumber();
        thMet = gd.getNextChoice();
        sigma1 = gd.getNextNumber();
        sigma2 = gd.getNextNumber();
        //sigmaz = gd.getNextNumber();
        cal.pixelWidth = gd.getNextNumber();
        cal.pixelHeight = cal.pixelWidth;
        cal.pixelDepth = gd.getNextNumber();
        aspect_ratio = cal.pixelDepth/cal.pixelWidth; 
        //radiusNei = gd.getNextNumber();
        //nbNei = (int)gd.getNextNumber();
     
    }
    
    public String getSpotChanel()
    {
        return spot_chanel_name;
    }
            
     
     
   /** public void getDistNeighbors(Object3D obj, Objects3DPopulation pop, DescriptiveStatistics cellNbNeighborsDistMean, 
           DescriptiveStatistics cellNbNeighborsDistMax) {
        DescriptiveStatistics stats = new DescriptiveStatistics();
        double[] dist= pop.kClosestDistancesSquared(obj.getCenterX(), obj.getCenterY(), obj.getCenterZ(), nbNei);
        for (double d : dist) {
           stats.addValue(Math.sqrt(d)); 
        }
        cellNbNeighborsDistMax.addValue(stats.getMax());
        cellNbNeighborsDistMean.addValue(stats.getMean());
    }*/
     
     /**
     * Write headers for results file
     * 
     * @param outDirResults
    */
    public void writeHeaders(String outDirResults) throws IOException 
    {
        // global results
        FileWriter fileResults = new FileWriter(outDirResults +"Results.xls", false);
        outPutResults = new BufferedWriter(fileResults);
        outPutResults.write("ImageName\tOocyteVolume(um3)\tNbSpots\t" 
                             +"SpotsVolume_Mean(um3)\tSpotsVolume_SD\tDistance2Center_Mean(um)\tDistance2Center_SD" 
                + "\tDistance2ClosestSpot_Mean(um)\tDistance2ClosestSpot_SD"
                +"\tMeanSpotIntensity\tMeanOocyteIntensityWithoutSpots\tNormalizedMeanSpotIntensity"
                +"\tSpotsSurfaceArea_Mean(um2)\tSpotsSurfaceArea_SD\tSpotsSphericity_Mean\tSpotsSphericity_SD\n");
        outPutResults.flush();
        
        // indivs results
        FileWriter fileDistances = new FileWriter(outDirResults +"Results_SpotsDistances.xls", false);
        outPutDistances = new BufferedWriter(fileDistances);
        outPutDistances.write("ImageName\tSpotIndex\tSpotVolume\tDistanceToCenter\tDistanceToClosestNeighbor\tMeanIntensity\tSpotSurfaceArea\tSpotSphericity\n");
        outPutDistances.flush();
        
    }
    
    public void writeIndividualSpotStat(String imgname, int ind, double distance, double volume, double dist2neighbor, double meanint, double surface, double sphericity)
    {
        try {
            imgname = imgname.replace(" ", "_");
            outPutDistances.write(imgname+"\t"+ind+"\t"+volume+"\t"+distance+"\t"+dist2neighbor+"\t"+meanint+"\t"+surface+"\t"+sphericity+"\n");
        } catch (IOException ex) {
            Logger.getLogger(SpotyTools.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public void writeOocyteStat( String imgName, double volume, int nspots ) throws IOException
    {
        imgName = imgName.replace(" ", "_");
        outPutResults.write(imgName+"\t"+volume+"\t"+nspots);
    }
    
    public void flushDistanceResults() throws IOException
    {
        outPutDistances.flush(); 
    }
    
    public void writeSpotsStat(int nspots, double[] vols, double sumvol, double[] distances, double sumdist, double[] dneigh, double sumdneigh, double[] meanints, double sumInt, double sumMeanInt, double ooTotInt, double ooVolume, double[] surfaces, double surfaceTot, double[] sphericities, double sphericityTot ) throws IOException 
    {
        double ooint = ooTotInt - sumInt;   // intensity excluding spots
        double ooVol = ooVolume - sumvol;  // volume excluding spots
        //System.out.println(ooVolume+" "+ooTotInt+" "+ooint+" "+sumInt+" "+sumvol);
        double meanSpotIntensity = sumMeanInt/nspots;
        double meanOoIntensity = ooint/ooVol;
        double meanSpotSurface = surfaceTot/nspots;
        double meanSpotSphericity = sphericityTot/nspots;
        outPutResults.write("\t"+(sumvol/nspots)+"\t"+getSD(vols, sumvol/nspots)+"\t"+(sumdist/nspots)+"\t"+getSD(distances, sumdist/nspots)
                + "\t"+(sumdneigh/nspots)+"\t"+getSD(dneigh, sumdneigh/nspots)+"\t"+meanSpotIntensity+"\t"+meanOoIntensity+"\t"
                +meanSpotIntensity/meanOoIntensity+"\t"
                +meanSpotSurface+"\t"+getSD(surfaces, meanSpotSurface)+"\t"
                +meanSpotSphericity+"\t"+getSD(sphericities, meanSpotSphericity)
                        +"\n");      
        outPutResults.flush();
    }
    
    
    public void closeResults() throws IOException 
    {
       outPutResults.close();
       outPutDistances.close();         
    }
}
