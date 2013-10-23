package cz.cuni.lf1.lge.ThunderSTORM;

import static cz.cuni.lf1.lge.ThunderSTORM.util.Math.min;
import static cz.cuni.lf1.lge.ThunderSTORM.util.ImageProcessor.subtract;
import static cz.cuni.lf1.lge.ThunderSTORM.util.ImageProcessor.add;
import cz.cuni.lf1.lge.ThunderSTORM.UI.AnalysisOptionsDialog;
import cz.cuni.lf1.lge.ThunderSTORM.UI.GUI;
import cz.cuni.lf1.lge.ThunderSTORM.UI.MacroParser;
import cz.cuni.lf1.lge.ThunderSTORM.UI.RenderingOverlay;
import cz.cuni.lf1.lge.ThunderSTORM.UI.StoppedByUserException;
import cz.cuni.lf1.lge.ThunderSTORM.detectors.IDetector;
import cz.cuni.lf1.lge.ThunderSTORM.detectors.ui.IDetectorUI;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.Molecule;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.MoleculeDescriptor;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.PSFModel;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.ui.IEstimatorUI;
import cz.cuni.lf1.lge.ThunderSTORM.filters.ui.IFilterUI;
import cz.cuni.lf1.lge.ThunderSTORM.rendering.IncrementalRenderingMethod;
import cz.cuni.lf1.lge.ThunderSTORM.rendering.RenderingQueue;
import cz.cuni.lf1.lge.ThunderSTORM.rendering.ui.IRendererUI;
import cz.cuni.lf1.lge.ThunderSTORM.results.IJResultsTable;
import cz.cuni.lf1.lge.ThunderSTORM.results.MeasurementProtocol;
import cz.cuni.lf1.lge.ThunderSTORM.thresholding.Thresholder;
import cz.cuni.lf1.lge.ThunderSTORM.util.Point;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.filter.ExtendedPlugInFilter;
import static ij.plugin.filter.PlugInFilter.DOES_16;
import static ij.plugin.filter.PlugInFilter.DOES_32;
import static ij.plugin.filter.PlugInFilter.DOES_8G;
import static ij.plugin.filter.PlugInFilter.DOES_STACKS;
import static ij.plugin.filter.PlugInFilter.DONE;
import static ij.plugin.filter.PlugInFilter.FINAL_PROCESSING;
import static ij.plugin.filter.PlugInFilter.NO_CHANGES;
import static ij.plugin.filter.PlugInFilter.NO_UNDO;
import static ij.plugin.filter.PlugInFilter.PARALLELIZE_STACKS;
import static ij.plugin.filter.PlugInFilter.SUPPORTS_MASKING;
import ij.plugin.filter.PlugInFilterRunner;
import ij.plugin.frame.Recorder;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import java.awt.Color;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

/**
 * ThunderSTORM Analysis plugin.
 *
 * Open the options dialog, process an image stack to recieve a list of
 * localized molecules which will get displayed in the {@code ResultsTable} and
 * previed in a new {@code ImageStack} with detections marked as crosses in
 * {@code Overlay} of each slice of the stack.
 */
public final class AnalysisPlugIn implements ExtendedPlugInFilter {

    private int stackSize;
    private AtomicInteger nProcessed = new AtomicInteger(0);
    private final int pluginFlags = DOES_8G | DOES_16 | DOES_32 | NO_CHANGES
            | NO_UNDO | DOES_STACKS | PARALLELIZE_STACKS | FINAL_PROCESSING | SUPPORTS_MASKING;
    private List<Molecule>[] results;
    private List<IFilterUI> allFilters;
    private List<IDetectorUI> allDetectors;
    private List<IEstimatorUI> allEstimators;
    private List<IRendererUI> allRenderers;
    private int selectedFilter;
    private int selectedEstimator;
    private int selectedDetector;
    private int selectedRenderer;
    private RenderingQueue renderingQueue;
    private ImagePlus renderedImage;
    private Roi roi;
    private MeasurementProtocol measurementProtocol;
    private AnalysisOptionsDialog dialog;

    /**
     * Returns flags specifying capabilities of the plugin.
     *
     * This method is called before an actual analysis and returns flags
     * supported by the plugin. The method is also called after the processing
     * is finished to fill the {@code ResultsTable} and to visualize the
     * detections directly in image stack (a new copy of image stack is
     * created).
     *
     * <strong>The {@code ResultsTable} is always guaranteed to contain columns
     * <i>frame, x, y</i>!</strong> The other parameters are optional and can
     * change for different PSFs.
     *
     * @param command command
     * @param imp ImagePlus instance holding the active image (not required)
     * @return flags specifying capabilities of the plugin
     */
    @Override
    public int setup(String command, ImagePlus imp) {
        GUI.setLookAndFeel();
        CameraSetupPlugIn.loadPreferences();
        //
        if(command.equals("final")) {
            IJ.showStatus("ThunderSTORM is generating the results...");
            //
            // Show table with results
            IJResultsTable rt = IJResultsTable.getResultsTable();
            rt.reset();
            rt.setOriginalState();
            for(int frame = 1; frame <= stackSize; frame++) {
                if(results[frame] != null) {
                    for(Molecule psf : results[frame]) {
                        psf.insertParamAt(0, MoleculeDescriptor.LABEL_FRAME, MoleculeDescriptor.Units.UNITLESS, (double) frame);
                        rt.addRow(psf);
                    }
                }
            }
            rt.convertAllColumnsToAnalogUnits();
            rt.insertIdColumn();
            rt.copyOriginalToActual();
            rt.setActualState();
            rt.setPreviewRenderer(renderingQueue);
            setDefaultColumnsWidth(rt);
            rt.setAnalyzedImage(imp);
            rt.setMeasurementProtocol(measurementProtocol);
            rt.show();
            //
            // Show detections in the image
            imp.setOverlay(null);
            RenderingOverlay.showPointsInImage(rt, imp, roi.getBounds(), Color.red, RenderingOverlay.MARKER_CROSS);
            renderingQueue.repaintLater();
            //
            // Finished
            IJ.showProgress(1.0);
            IJ.showStatus("ThunderSTORM finished.");
            return DONE;
        } else if("showResultsTable".equals(command)) {
            IJResultsTable.getResultsTable().show();
            return DONE;
        } else {
            return pluginFlags; // Grayscale only, no changes to the image and therefore no undo
        }
    }

    /**
     * Show the options dialog for a particular command and block the current
     * processing thread until user confirms his settings or cancels the
     * operation.
     *
     * @param command command (not required)
     * @param imp ImagePlus instance holding the active image (not required)
     * @param pfr instance that initiated this plugin (not required)
     * @return
     */
    @Override
    public int showDialog(final ImagePlus imp, final String command, PlugInFilterRunner pfr) {
        try {
            // load modules
            allFilters = ModuleLoader.getUIModules(IFilterUI.class);
            allDetectors = ModuleLoader.getUIModules(IDetectorUI.class);
            allEstimators = ModuleLoader.getUIModules(IEstimatorUI.class);
            allRenderers = ModuleLoader.getUIModules(IRendererUI.class);

            if(MacroParser.isRanFromMacro()) {
                //parse the macro options
                MacroParser parser = new MacroParser(allFilters, allEstimators, allDetectors, allRenderers);
                selectedFilter = parser.getFilterIndex();
                selectedDetector = parser.getDetectorIndex();
                selectedEstimator = parser.getEstimatorIndex();

                roi = imp.getRoi() != null ? imp.getRoi() : new Roi(0, 0, imp.getWidth(), imp.getHeight());
                IRendererUI rendererPanel = parser.getRendererUI();
                rendererPanel.setSize(roi.getBounds().width, roi.getBounds().height);
                IncrementalRenderingMethod method = rendererPanel.getImplementation();
                renderedImage = (method != null) ? method.getRenderedImage() : null;
                renderingQueue = new RenderingQueue(method, new RenderingQueue.DefaultRepaintTask(renderedImage), rendererPanel.getRepaintFrequency());

                measurementProtocol = new MeasurementProtocol(imp, allFilters.get(selectedFilter), allDetectors.get(selectedDetector), allEstimators.get(selectedEstimator));

            } else {
                // Create and show the dialog
                try {
                    SwingUtilities.invokeAndWait(new Runnable() {
                        @Override
                        public void run() {
                            dialog = new AnalysisOptionsDialog(imp, command, allFilters, allDetectors, allEstimators, allRenderers);
                            dialog.setVisible(true);
                        }
                    });
                } catch(InvocationTargetException e) {
                    throw e.getCause();
                }

                if(dialog.wasCanceled()) {  // This is a blocking call!!
                    return DONE;    // cancel
                }
                selectedFilter = dialog.getFilterIndex();
                selectedDetector = dialog.getDetectorIndex();
                selectedEstimator = dialog.getEstimatorIndex();
                selectedRenderer = dialog.getRendererIndex();

                roi = imp.getRoi() != null ? imp.getRoi() : new Roi(0, 0, imp.getWidth(), imp.getHeight());
                IRendererUI renderer = allRenderers.get(selectedRenderer);
                renderer.setSize(roi.getBounds().width, roi.getBounds().height);
                IncrementalRenderingMethod method = renderer.getImplementation();
                renderedImage = (method != null) ? method.getRenderedImage() : null;
                renderingQueue = new RenderingQueue(method, new RenderingQueue.DefaultRepaintTask(renderedImage), renderer.getRepaintFrequency());

                //if recording window is open, record parameters of all modules
                if(Recorder.record) {
                    MacroParser.recordFilterUI(dialog.getFilter());
                    MacroParser.recordDetectorUI(dialog.getDetector());
                    MacroParser.recordEstimatorUI(dialog.getEstimator());
                    MacroParser.recordRendererUI(dialog.getRenderer());
                }

                measurementProtocol = new MeasurementProtocol(imp, dialog.getFilter(), dialog.getDetector(), dialog.getEstimator());
            }
        } catch(Throwable ex) {
            IJ.handleException(ex);
            return DONE;
        }
        //
        try {
            Thresholder.loadFilters(allFilters);
            Thresholder.setActiveFilter(selectedFilter);   // !! must be called before any threshold is evaluated !!
            Thresholder.parseThreshold(allDetectors.get(selectedDetector).getThreadLocalImplementation().getThresholdFormula());
        } catch(Exception ex) {
            IJ.error("Error parsing threshold formula! " + ex.toString());
            return DONE;
        }
        //
        return pluginFlags; // ok
    }

    /**
     * Gives the plugin information about the number of passes through the image
     * stack we want to process.
     *
     * Allocation of resources to store the results is done here.
     *
     * @param nPasses number of passes through the image stack we want to
     * process
     */
    @Override
    public void setNPasses(int nPasses) {
        stackSize = nPasses;
        nProcessed.set(0);
        results = new Vector[stackSize + 1];  // indexing from 1 for simplicity
    }

    /**
     * Run the plugin.
     *
     * This method is ran in parallel, thus counting the results must be done
     * atomicaly.
     *
     * @param ip input image
     */
    @Override
    public void run(ImageProcessor ip) {
        assert (selectedFilter >= 0 && selectedFilter < allFilters.size()) : "Index out of bounds: selectedFilter!";
        assert (selectedDetector >= 0 && selectedDetector < allDetectors.size()) : "Index out of bounds: selectedDetector!";
        assert (selectedEstimator >= 0 && selectedEstimator < allEstimators.size()) : "Index out of bounds: selectedEstimator!";
        assert (selectedRenderer >= 0 && selectedRenderer < allRenderers.size()) : "Index out of bounds: selectedRenderer!";
        assert (renderingQueue != null) : "Renderer was not selected!";
        //
        ip.setRoi(roi);
        FloatProcessor fp = subtract((FloatProcessor) ip.crop().convertToFloat(), (float) CameraSetupPlugIn.offset);
        float minVal = min((float[]) fp.getPixels());
        if(minVal < 0) {
            IJ.log("Camera base level is set higher than values in the image!");
            fp = add(-minVal, fp);
        }
        if(roi != null) {
            fp.setMask(roi.getMask());
        }
        Vector<Molecule> fits;
        try {
            Thresholder.setCurrentImage(fp);
            FloatProcessor filtered = allFilters.get(selectedFilter).getThreadLocalImplementation().filterImage(fp);
            IDetector detector = allDetectors.get(selectedDetector).getThreadLocalImplementation();
            Vector<Point> detections = detector.detectMoleculeCandidates(filtered);
            fits = allEstimators.get(selectedEstimator).getThreadLocalImplementation().estimateParameters(fp, Point.applyRoiMask(roi, detections));
            results[ip.getSliceNumber()] = fits;
            nProcessed.incrementAndGet();

            if(fits.size() > 0) {
                renderingQueue.renderLater(fits);
            }
            //
            IJ.showProgress((double) nProcessed.intValue() / (double) stackSize);
            IJ.showStatus("ThunderSTORM processing frame " + nProcessed + " of " + stackSize + "...");
        }catch (StoppedByUserException ie){
            //escape pressed flag is not reset and is handled by caller
        } catch(Exception ex) {
            IJ.handleException(ex);
        }
    }

    public static void setDefaultColumnsWidth(IJResultsTable rt) {
        rt.setColumnPreferredWidth(MoleculeDescriptor.LABEL_ID, 40);
        rt.setColumnPreferredWidth(MoleculeDescriptor.LABEL_FRAME, 40);
        rt.setColumnPreferredWidth(PSFModel.Params.LABEL_X, 60);
        rt.setColumnPreferredWidth(PSFModel.Params.LABEL_Y, 60);
        rt.setColumnPreferredWidth(PSFModel.Params.LABEL_SIGMA, 40);
        rt.setColumnPreferredWidth(PSFModel.Params.LABEL_SIGMA1, 40);
        rt.setColumnPreferredWidth(PSFModel.Params.LABEL_SIGMA1, 40);
    }
}
