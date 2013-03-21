package ThunderSTORM.filters;

import ThunderSTORM.IModule;
import ThunderSTORM.utils.GridBagHelper;
import ij.IJ;
import ij.process.FloatProcessor;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

public final class MedianFilter implements IFilter, IModule {

    public static final int CROSS = 4;
    public static final int BOX = 8;
    private int pattern;
    private int size;
    
    private JTextField sizeTextField;
    private JRadioButton patternCrossRadioButton, patternBoxRadioButton;

    public MedianFilter(int pattern, int size) {
        assert ((pattern == BOX) || (pattern == CROSS));

        this.pattern = pattern;
        this.size = size;
    }

    @Override
    public FloatProcessor filterImage(FloatProcessor image) {
        FloatProcessor result = new FloatProcessor(image.getWidth(), image.getHeight());
        if (pattern == BOX) {
            float [] items = new float[size*size];
            for (int x = 0, xm = image.getWidth(); x < xm; x++) {
                for (int y = 0, ym = image.getHeight(); y < ym; y++) {
                    int ii = 0;
                    for(int i = x - size/2, im = i + size; i < im; i++) {
                        for(int j = x - size/2, jm = j + size; j < jm; j++) {
                            if((i >= 0) && (i < xm) && (j >= 0) && (j < ym)) {
                                items[ii] = image.getPixelValue(i, j);
                                ii++;
                            }
                        }
                    }
                    result.setf(x, y, items[ii/2]);
                }
            }
        } else {
            float [] items = new float[2*size];
            for (int x = 0, xm = image.getWidth(); x < xm; x++) {
                for (int y = 0, ym = image.getHeight(); y < ym; y++) {
                    int ii = 0;
                    for(int i = x - size/2, im = i + size; i < im; i++) {
                        if((i >= 0) && (i < xm)) {
                            items[ii] = image.getPixelValue(i, y);
                            ii++;
                        }
                    }
                    for(int j = y - size/2, jm = j + size; j < jm; j++) {
                        if((j >= 0) && (j < ym)) {
                            items[ii] = image.getPixelValue(x, j);
                            ii++;
                        }
                    }
                    result.setf(x, y, items[ii/2]);
                }
            }
        }
        return result;
    }

    @Override
    public String getName() {
        return "Median filter";
    }

    @Override
    public JPanel getOptionsPanel() {
        patternBoxRadioButton = new JRadioButton("box");
        patternCrossRadioButton = new JRadioButton("cross");
        sizeTextField = new JTextField(Integer.toString(size), 20);
        //
        patternBoxRadioButton.setSelected(pattern == BOX);
        patternCrossRadioButton.setSelected(pattern == CROSS);
        //
        JPanel panel = new JPanel(new GridBagLayout());
        panel.add(new JLabel("Pattern: "), GridBagHelper.pos(0, 0));
        panel.add(patternBoxRadioButton, GridBagHelper.pos(1, 0));
        panel.add(patternCrossRadioButton, GridBagHelper.pos(1, 1));
        panel.add(new JLabel("Size: "), GridBagHelper.pos(0, 2));
        panel.add(sizeTextField, GridBagHelper.pos(1, 2));
        return panel;
    }

    @Override
    public void readParameters() {
        try {
            size = Integer.parseInt(sizeTextField.getText());
            if(patternBoxRadioButton.isSelected()) pattern = BOX;
            if(patternCrossRadioButton.isSelected()) pattern = CROSS;
        } catch(NumberFormatException ex) {
            IJ.showMessage("Error!", ex.getMessage());
        }
    }
}
