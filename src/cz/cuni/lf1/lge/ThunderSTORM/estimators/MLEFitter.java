package cz.cuni.lf1.lge.ThunderSTORM.estimators;

import cz.cuni.lf1.lge.ThunderSTORM.CameraSetupPlugIn;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.Molecule;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.MoleculeDescriptor;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.PSFModel;
import static cz.cuni.lf1.lge.ThunderSTORM.util.Math.sub;
import static cz.cuni.lf1.lge.ThunderSTORM.util.Math.var;
import org.apache.commons.math3.optim.InitialGuess;
import org.apache.commons.math3.optim.MaxEval;
import org.apache.commons.math3.optim.MaxIter;
import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;
import org.apache.commons.math3.optim.nonlinear.scalar.ObjectiveFunction;
import org.apache.commons.math3.optim.nonlinear.scalar.noderiv.NelderMeadSimplex;
import org.apache.commons.math3.optim.nonlinear.scalar.noderiv.SimplexOptimizer;

public class MLEFitter implements OneLocationFitter {

    PSFModel psfModel;
    double [] fittedModelValues;
    final static int MAX_ITERATIONS = 50000;

    public MLEFitter(PSFModel psfModel) {
        this.psfModel = psfModel;
        this.fittedModelValues = null;
    }

    @Override
    public Molecule fit(SubImage subimage) {

        if((fittedModelValues == null) || (fittedModelValues.length < subimage.values.length)) {
            fittedModelValues = new double[subimage.values.length];
        }
        
        SimplexOptimizer optimizer = new SimplexOptimizer(1e-10, 1e-10);
        PointValuePair pv;

        pv = optimizer.optimize(
                MaxEval.unlimited(),
                new MaxIter(MAX_ITERATIONS),
                new ObjectiveFunction(psfModel.getLikelihoodFunction(subimage.xgrid, subimage.ygrid, subimage.values)),
                new InitialGuess(psfModel.transformParametersInverse(psfModel.getInitialParams(subimage))),
                GoalType.MINIMIZE,
                new NelderMeadSimplex(psfModel.getInitialSimplex()));
        
        // estimate background, calculate the Thompson formula, and return an instance of the `Molecule`
        double[] point = pv.getPointRef();
        point[PSFModel.Params.BACKGROUND] = var(sub(fittedModelValues, subimage.values, psfModel.getValueFunction(subimage.xgrid, subimage.ygrid).value(point)));
        Molecule mol = psfModel.newInstanceFromParams(psfModel.transformParameters(point));
        try {
            String paramName;
            double paramValue;
            if(CameraSetupPlugIn.isEmCcd) {
                paramName = MoleculeDescriptor.Fitting.LABEL_EMCCD_THOMPSON;
                paramValue = MoleculeDescriptor.Fitting.emccdThompson(mol);
            } else {
                paramName = MoleculeDescriptor.Fitting.LABEL_CCD_THOMPSON;
                paramValue = MoleculeDescriptor.Fitting.ccdThompson(mol);
            }
            mol.addParam(paramName, MoleculeDescriptor.Units.getDefaultUnit(paramName), paramValue);
        } catch(Exception e) {
            // ignore...PSF does not fit all the required parameters
        }
        return mol;
    }
}
