package SpotyDist;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.Roi;
import ij.gui.WaitForUserDialog;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import loci.common.services.ServiceFactory;
import loci.formats.meta.IMetadata;
import loci.formats.services.OMEXMLService;
import loci.plugins.util.ImageProcessorReader;
import mcib3d.geom.Object3D;
import mcib3d.geom.Objects3DPopulation;
import org.apache.commons.io.FilenameUtils;


/**
 *
 * @author gletort
 */
        
        
public class Spoty_Distribution implements PlugIn 
{
    
    private boolean canceled;
    private String imageDir;
    private static String outDirResults;
    public static Calibration cal = new Calibration();
    private SpotyTools tools = new SpotyTools();
    
    /**
     * 
     * @param arg
     */
    @Override
    public void run(String arg) {
        try {
            if (canceled) {
                IJ.showMessage(" Pluging canceled");
                return;
            }
            if (!tools.checkInstalledModules()) {
                IJ.showMessage(" Pluging canceled");
                return;
            }
            imageDir = IJ.getDirectory("Folder to analyse");
            if (imageDir == null) {
                return;
            }
            File inDir = new File(imageDir);
            String fileExt = tools.findImageType(inDir);
            ArrayList<String> imageFiles = tools.findImages(imageDir, fileExt);
            if (imageFiles == null) {
                IJ.showMessage(" No images with the correct format found in "+imageDir);
                return;
            }
            
            // create output folder
            outDirResults = imageDir + "Results"+ File.separator;
            File outDir = new File(outDirResults);
            if (!Files.exists(Paths.get(outDirResults))) {
                outDir.mkdir();
            }
            
            // create OME-XML metadata store of the latest schema version
            ServiceFactory factory;
            factory = new ServiceFactory();
            OMEXMLService service = factory.getInstance(OMEXMLService.class);
            IMetadata meta = service.createOMEXMLMetadata();
            ImageProcessorReader reader = new ImageProcessorReader();
            reader.setMetadataStore(meta);
            // Find channel names , calibration
            reader.setId(imageFiles.get(0));
            reader.setSeries(1);
            tools.setCalibration(meta);
            int nchans = reader.getSizeC();
            String[] channels = new String[nchans];
            reader.setSeries(1);
            for ( int chan=0; chan<nchans; chan++ )
            {
                channels[chan] = meta.getChannelName(1, chan).toString();
            }
            tools.dialog(channels);
            String spotchanel = tools.getSpotChanel();
            
            // Initialise everything 
            tools.writeHeaders(outDirResults);
            File dir = new File(imageDir);
            
            // Let's go, process all files
            int ooIndex = 0;
            for (String f : dir.list()) 
            {    
                if (f.contains(spotchanel))
                {
                     String rootName = FilenameUtils.getBaseName(f);
                     String filename = rootName+".TIF";
                     process_image( filename );
                   
                }
            }
            tools.closeResults();
            IJ.showStatus("Processing done....");
            } catch (Exception ex) {
            Logger.getLogger(Spoty_Distribution.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    /**
     * Process one image: find the oocyte contour, then the spots (and the DNA?)
     * @param filename 
     */
    public void process_image( String filename )
    {
          ImagePlus img = IJ.openImage(imageDir+filename);
          img.show();
          tools.setImageCalibration(img);
          IJ.log("Preprocessing image: "+filename+" to find oocytes...");
          //ImagePlus med_img = tools.apply_median_filter(img, 4);
          ImagePlus med_img = img.duplicate();
          IJ.run(med_img, "Gaussian Blur 3D...", "x=3 y=3 z=2");
          med_img.show();
          tools.close(img);
          tools.apply_threshold(med_img, "Otsu");
          Prefs.blackBackground = true;
          IJ.log("Get oocytes...");
          IJ.run(med_img, "Dilate", "stack");
          IJ.run(med_img, "Close-", "stack");
          IJ.run(med_img, "Fill Holes", "stack");
          IJ.run(med_img, "Minimum...", "radius=20 stack");
          IJ.run(med_img, "Maximum...", "radius=20 stack");
          ArrayList<Object3D> oocytes = tools.getObjectsFromImage(med_img, 500, 1000000000);
          IJ.log("Number of oocytes found in image: "+oocytes.size());
          tools.close(med_img);
           
          for ( int i=0; i<oocytes.size(); i++ )
          {
              Oocyte3D oo = new Oocyte3D(oocytes.get(i), tools, outDirResults);
              String purename = tools.getPureName(filename);
              oo.setName( purename, i );
              // Find the spots
              oo.getSpots();
              // Measures properties
              oo.doMeasurements();
          }
    }
    

}

