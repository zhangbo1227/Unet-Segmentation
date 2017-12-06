import ij.*;
import ij.plugin.*;
import ij.plugin.frame.*;
import ij.gui.*;
import ij.process.*;
import ij.measure.Calibration;

import java.lang.*;
import java.util.*;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.FileNameExtensionFilter;

import java.io.*;
import java.util.Vector;
import java.util.UUID;

import com.jcraft.jsch.*;

import ncsa.hdf.hdf5lib.exceptions.HDF5Exception;
import ch.systemsx.cisd.hdf5.HDF5Factory;
import ch.systemsx.cisd.hdf5.IHDF5Reader;

public class UnetJob extends Thread implements PlugIn {

  protected boolean _isInteractive = true;
  protected boolean _finished = false;

  protected final String _jobId = "unet-" + UUID.randomUUID().toString();

  protected UnetJobTableModel _jobTableModel = null;

  private TaskStatus _taskStatus = new TaskStatus();
  private long _taskProgressMin = 0;
  private long _taskProgressMax = 0;
  private long _jobProgress = 0;
  protected JButton _readyCancelButton = new JButton("Cancel");

  protected JDialog _parametersDialog = null;
  protected GroupLayout _dialogLayout = null;


  protected JComboBox<ModelDefinition> _modelComboBox =
      new JComboBox<ModelDefinition>();
  protected JTextField _weightsFileTextField = new JTextField("", 20);
  protected JTextField _processFolderTextField = new JTextField(
      Prefs.get("unet_segmentation.processfolder", ""), 20);
  private final String[] gpuList = {
      "none", "all available", "GPU 0", "GPU 1", "GPU 2", "GPU 3",
      "GPU 4", "GPU 5", "GPU 6", "GPU 7" };
  protected JComboBox<String> _useGPUComboBox = new JComboBox<>(gpuList);

  protected HostConfigurationPanel _hostConfiguration = null;

  protected Session _sshSession;

  protected Vector<String> _createdRemoteFolders = new Vector<String>();
  protected Vector<String> _createdRemoteFiles = new Vector<String>();

  protected File _modelFolder = null;

  public String id() {
    return _jobId;
  }

  public void setJobTableModel(UnetJobTableModel jobTableModel) {
    _jobTableModel = jobTableModel;
  }

  public ModelDefinition model() {
    if (_modelComboBox.getSelectedItem() instanceof ModelDefinition)
        return (ModelDefinition)_modelComboBox.getSelectedItem();
    else return null;
  }

  public String weightsFileName() {
    return _weightsFileTextField.getText();
  }

  public String hostname() {
    if (_sshSession != null) return _sshSession.getHost();
    else return "localhost";
  }

  public void setTaskProgress(String status, long progress, long maxProgress) {
    _taskStatus.name = status;
    _taskStatus.progress = progress;
    _taskStatus.maxProgress = maxProgress;
    _taskStatus.isIndeterminate = (maxProgress == 0);
    if (_jobTableModel != null) {
      SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
              _jobTableModel.updateJobStatus(_jobId);
            }});
    }
    else IJ.showStatus(_taskStatus.name);
    IJ.log(status);
  }

  public TaskStatus status() {
    return _taskStatus;
  }

  public void setTaskProgress(long progress, long maxProgress) {
    _taskStatus.progress = progress;
    _taskStatus.maxProgress = maxProgress;
    _taskStatus.isIndeterminate = (maxProgress == 0);
    if (_jobTableModel != null) {
      SwingUtilities.invokeLater(
          new Runnable() {
            @Override
            public void run() {
              _jobTableModel.updateJobStatus(_jobId);
            }});
    }
  }

  public void setTaskProgressRange(long min, long max) {
    _taskProgressMin = min;
    _taskProgressMax = max;
  }

  public void setTaskProgressMin(long min) {
    _taskProgressMin = min;
  }

  public long getTaskProgressMin() {
    return _taskProgressMin;
  }

  public void setTaskProgressMax(long max) {
    _taskProgressMax = max;
  }

  public long getTaskProgressMax() {
    return _taskProgressMax;
  }

  public long progress() {
    return _jobProgress;
  }

  public void setProgress(long progress, long progressMax) {
    _jobProgress = (100 * progress) / progressMax;
    if (_jobTableModel != null) {
      SwingUtilities.invokeLater(
          new Runnable() {
            @Override
            public void run() {
              _jobTableModel.updateJobProgress(_jobId);
            }});
    }
    else IJ.showProgress(_jobProgress / 100.0);
  }

  public void setProgress(long progress) {
    setProgress(progress, 100);
  }

  public JButton readyCancelButton() {
    return _readyCancelButton;
  }

  public boolean ready() {
    return _readyCancelButton.getText().equals("Ready");
  }

  protected void setReady(boolean ready) {
    _readyCancelButton.setText("Ready");
    if (_jobTableModel == null) return;
    SwingUtilities.invokeLater(
        new Runnable() {
          @Override
          public void run() {
            _jobTableModel.updateJobDownloadEnabled(_jobId);
          }});
  }

  public void finish() {
    if (_finished) return;
    cleanUp();
  }

  private void searchModels() {
    _modelComboBox.removeAllItems();
    if (_modelFolder != null && _modelFolder.isDirectory()) {
      File[] files = _modelFolder.listFiles(
          new FileFilter() {
            @Override public boolean accept(File file) {
              return file.getName().matches(".*[.]h5$");
            }});
      for (int i = 0; i < files.length; i++) {
        try {
          ModelDefinition model = UnetTools.loadModel(files[i]);
          if (model != null) _modelComboBox.addItem(model);
        }
        catch (HDF5Exception e) {}
      }
      if (_modelComboBox.getItemCount() == 0)
          _modelComboBox.addItem(new ModelDefinition());
      else {
        String modelName = Prefs.get("unet_segmentation.modelName", "");
        for (int i = 0; i < _modelComboBox.getItemCount(); i++) {
          if (_modelComboBox.getItemAt(i).name.equals(modelName)) {
            _modelComboBox.setSelectedIndex(i);
            break;
          }
        }
        Prefs.set("unet_segmentation.modelDefinitionFolder",
                  _modelFolder.getAbsolutePath());
      }
    }
    _parametersDialog.invalidate();
    _parametersDialog.pack();
    _parametersDialog.setMinimumSize(_parametersDialog.getPreferredSize());
    _parametersDialog.setMaximumSize(
        new Dimension(_parametersDialog.getMaximumSize().width,
                      _parametersDialog.getPreferredSize().height));
    _parametersDialog.validate();
  }

  public void prepareParametersDialog() {
    /*******************************************************************
     * Generate the GUI layout without logic
     *******************************************************************/

    // Host configuration
    _hostConfiguration = new HostConfigurationPanel();

    // Model selection
    final JLabel modelLabel = new JLabel("Model:");
    _modelComboBox.setToolTipText("Select a caffe model.");
    final JButton modelFolderChooseButton;
    if (UIManager.get("FileView.directoryIcon") instanceof Icon)
        modelFolderChooseButton = new JButton(
            (Icon)UIManager.get("FileView.directoryIcon"));
    else modelFolderChooseButton = new JButton("...");
    int marginTop = (int) Math.ceil(
        (modelFolderChooseButton.getPreferredSize().getHeight() -
         _modelComboBox.getPreferredSize().getHeight()) / 2.0);
    int marginBottom = (int) Math.floor(
        (modelFolderChooseButton.getPreferredSize().getHeight() -
         _modelComboBox.getPreferredSize().getHeight()) / 2.0);
    Insets insets = modelFolderChooseButton.getMargin();
    insets.top -= marginTop;
    insets.left = 1;
    insets.bottom -= marginBottom;
    insets.right = 1;
    modelFolderChooseButton.setMargin(insets);
    modelFolderChooseButton.setToolTipText(
        "Select local model definition folder");

    // Weights
    final JLabel weightsFileLabel = new JLabel("Weight file:");
    _weightsFileTextField.setToolTipText(
        "Location of the file containing the trained network weights " +
        "on the backend server.\nIf not yet on the server, on-the-fly " +
        "file upload will be offered.");
    final JButton weightsFileChooseButton =
        _hostConfiguration.weightsFileChooseButton();
    marginTop = (int) Math.ceil(
        (weightsFileChooseButton.getPreferredSize().getHeight() -
         _weightsFileTextField.getPreferredSize().getHeight()) / 2.0);
    marginBottom = (int) Math.floor(
        (weightsFileChooseButton.getPreferredSize().getHeight() -
         _weightsFileTextField.getPreferredSize().getHeight()) / 2.0);
    insets = weightsFileChooseButton.getMargin();
    insets.top -= marginTop;
    insets.left = 1;
    insets.bottom -= marginBottom;
    insets.right = 1;
    weightsFileChooseButton.setMargin(insets);
    weightsFileChooseButton.setToolTipText(
        "Choose the file containing the trained network weights.");

    // Processing environment
    final JLabel processFolderLabel = new JLabel("Process Folder:");
    _processFolderTextField.setToolTipText(
        "Folder for temporary files on the backend server.");
    final JButton processFolderChooseButton =
        _hostConfiguration.processFolderChooseButton();
    marginTop = (int) Math.ceil(
        (processFolderChooseButton.getPreferredSize().getHeight() -
         _processFolderTextField.getPreferredSize().getHeight()) / 2.0);
    marginBottom = (int) Math.floor(
        (processFolderChooseButton.getPreferredSize().getHeight() -
         _processFolderTextField.getPreferredSize().getHeight()) / 2.0);
    insets = processFolderChooseButton.getMargin();
    insets.top -= marginTop;
    insets.left = 1;
    insets.bottom -= marginBottom;
    insets.right = 1;
    processFolderChooseButton.setMargin(insets);
    processFolderChooseButton.setToolTipText(
        "Select the folder to store temporary files.");

    // GPU parameters
    final JLabel useGPULabel = new JLabel("Use GPU:");
    _useGPUComboBox.setToolTipText(
        "Select the GPU id to use. Select CPU if you don't have any " +
        "CUDA capable GPU available on the compute host. Select " +
        "<autodetect> to leave the choice to caffe.");

    // Create Parameters Panel
    final JPanel dialogPanel = new JPanel();
    dialogPanel.setBorder(BorderFactory.createEtchedBorder());
    GroupLayout layout = new GroupLayout(dialogPanel);
    dialogPanel.setLayout(layout);
    layout.setAutoCreateGaps(true);
    layout.setAutoCreateContainerGaps(true);
    layout.setHorizontalGroup(
        layout.createParallelGroup(GroupLayout.Alignment.LEADING)
        .addGroup(
            layout.createSequentialGroup()
            .addGroup(
                layout.createParallelGroup(GroupLayout.Alignment.LEADING)
                .addComponent(modelLabel)
                .addComponent(weightsFileLabel)
                .addComponent(processFolderLabel)
                .addComponent(useGPULabel)
                .addComponent(tilingModeSelectorPanel))
            .addGroup(
                layout.createParallelGroup(GroupLayout.Alignment.LEADING)
                .addGroup(
                    layout.createSequentialGroup()
                    .addComponent(_modelComboBox)
                    .addComponent(modelFolderChooseButton))
                .addGroup(
                    layout.createSequentialGroup()
                    .addComponent(_weightsFileTextField)
                    .addComponent(weightsFileChooseButton))
                .addGroup(
                    layout.createSequentialGroup()
                    .addComponent(_processFolderTextField)
                    .addComponent(processFolderChooseButton))
                .addComponent(_useGPUComboBox)
                .addComponent(tilingParametersPanel)))
        .addComponent(_hostConfiguration));

    layout.setVerticalGroup(
        layout.createSequentialGroup()
        .addGroup(
            layout.createParallelGroup(GroupLayout.Alignment.BASELINE)
            .addComponent(modelLabel)
            .addComponent(_modelComboBox)
            .addComponent(modelFolderChooseButton))
        .addGroup(
            layout.createParallelGroup(GroupLayout.Alignment.BASELINE)
            .addComponent(weightsFileLabel)
            .addComponent(_weightsFileTextField)
            .addComponent(weightsFileChooseButton))
        .addGroup(
            layout.createParallelGroup(GroupLayout.Alignment.BASELINE)
            .addComponent(processFolderLabel)
            .addComponent(_processFolderTextField)
            .addComponent(processFolderChooseButton))
        .addGroup(
            layout.createParallelGroup(GroupLayout.Alignment.BASELINE)
            .addComponent(useGPULabel)
            .addComponent(_useGPUComboBox))
        .addGroup(
            layout.createParallelGroup(GroupLayout.Alignment.CENTER)
            .addComponent(
                tilingModeSelectorPanel, GroupLayout.PREFERRED_SIZE,
                GroupLayout.PREFERRED_SIZE, GroupLayout.PREFERRED_SIZE)
            .addComponent(
                tilingParametersPanel, GroupLayout.PREFERRED_SIZE,
                GroupLayout.PREFERRED_SIZE, GroupLayout.PREFERRED_SIZE))
        .addComponent(_hostConfiguration));
    dialogPanel.setMaximumSize(
        new Dimension(
            Integer.MAX_VALUE, dialogPanel.getPreferredSize().height));

    // Create config panel
    final JPanel configPanel = new JPanel();
    configPanel.add(_keepOriginalCheckBox);
    configPanel.add(_outputScoresCheckBox);

    // OK/Cancel buttons
    final JButton okButton = new JButton("OK");
    final JButton cancelButton = new JButton("Cancel");
    final JPanel okCancelPanel = new JPanel();
    okCancelPanel.add(okButton);
    okCancelPanel.add(cancelButton);

    // Assemble button panel
    final JPanel buttonPanel = new JPanel(new BorderLayout());
    buttonPanel.add(configPanel, BorderLayout.WEST);
    buttonPanel.add(okCancelPanel, BorderLayout.EAST);

    // Assemble Dialog
    _parametersDialog = new JDialog(
        _imp.getWindow(), "U-net segmentation", true);
    _parametersDialog.add(dialogPanel, BorderLayout.CENTER);
    _parametersDialog.add(buttonPanel, BorderLayout.SOUTH);
    _parametersDialog.getRootPane().setDefaultButton(okButton);

    /*******************************************************************
     * Wire controls inner to outer before setting values so that
     * value changes trigger all required updates
     *******************************************************************/
    // Model selection affects weightFileTextField, tileModeSelector and
    // tilingParameters (critical)
    _modelComboBox.addItemListener(
        new ItemListener() {
          @Override
          public void itemStateChanged(ItemEvent e) {
            if (e.getStateChange() == ItemEvent.SELECTED) {
              tilingModeSelectorPanel.removeAll();
              tilingParametersPanel.removeAll();
              if (model() != null) {
                _weightsFileTextField.setText(model().weightFile);
                tilingModeSelectorPanel.add(model().tileModeSelector);
                tilingModeSelectorPanel.setMinimumSize(
                    model().tileModeSelector.getPreferredSize());
                tilingModeSelectorPanel.setMaximumSize(
                    model().tileModeSelector.getPreferredSize());
                tilingParametersPanel.add(model().tileModePanel);
                tilingParametersPanel.setMinimumSize(
                    model().tileModePanel.getMinimumSize());
                tilingParametersPanel.setMaximumSize(
                    new Dimension(
                        Integer.MAX_VALUE,
                        model().tileModeSelector.getPreferredSize().height));
              }
              dialogPanel.setMaximumSize(
                  new Dimension(
                      Integer.MAX_VALUE,
                      dialogPanel.getPreferredSize().height));
              _parametersDialog.invalidate();
              _parametersDialog.setMinimumSize(
                  _parametersDialog.getPreferredSize());
              _parametersDialog.setMaximumSize(
                  new Dimension(
                      Integer.MAX_VALUE,
                      _parametersDialog.getPreferredSize().height));
              _parametersDialog.validate();
            }
          }});

    // Model folder selection affects ModelComboBox (critical)
    modelFolderChooseButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            File startFolder =
                (model() == null || model().file == null ||
                 model().file.getParentFile() == null) ?
                new File(".") : model().file.getParentFile();
            JFileChooser f = new JFileChooser(startFolder);
            f.setDialogTitle("Select U-net model folder");
            f.setMultiSelectionEnabled(false);
            f.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int res = f.showDialog(_parametersDialog, "Select");
            if (res != JFileChooser.APPROVE_OPTION) return;
            _modelFolder = f.getSelectedFile();
            // This also updates the Prefs if the folder contains valid models
            searchModels();
          }});

    // WeightsFileTextField affects model.weightFile (not critical)
    _weightsFileTextField.getDocument().addDocumentListener(
        new DocumentListener() {
          @Override
          public void insertUpdate(DocumentEvent e) {
            if (model() != null)
                model().weightFile = _weightsFileTextField.getText();
          }
          @Override
          public void removeUpdate(DocumentEvent e) {
            if (model() != null)
                model().weightFile = _weightsFileTextField.getText();
          }
          @Override
          public void changedUpdate(DocumentEvent e) {
            if (model() != null)
                model().weightFile = _weightsFileTextField.getText();
          }
        });

    // WeightsFileChooser affects WeightsFileTextField (not critical)
    weightsFileChooseButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            File startFile = new File(_weightsFileTextField.getText());
            JFileChooser f = new JFileChooser(startFile);
            f.setDialogTitle("Select trained U-net weights");
            f.setFileFilter(
                new FileNameExtensionFilter(
                    "HDF5 and prototxt files", "h5", "H5",
                    "prototxt", "PROTOTXT", "caffemodel",
                    "CAFFEMODEL"));
            f.setMultiSelectionEnabled(false);
            f.setFileSelectionMode(JFileChooser.FILES_ONLY);
            int res = f.showDialog(_parametersDialog, "Select");
            if (res != JFileChooser.APPROVE_OPTION) return;
            _weightsFileTextField.setText(
                f.getSelectedFile().getAbsolutePath());
            model().weightFile = _weightsFileTextField.getText();
          }});

    // ProcessFolderChooser affects ProcessFolderTextField (not critical)
    processFolderChooseButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            File startFolder = new File(_processFolderTextField.getText());
            JFileChooser f = new JFileChooser(startFolder);
            f.setDialogTitle("Select (remote) processing folder");
            f.setMultiSelectionEnabled(false);
            f.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int res = f.showDialog(_parametersDialog, "Select");
            if (res != JFileChooser.APPROVE_OPTION) return;
            _processFolderTextField.setText(
                f.getSelectedFile().getAbsolutePath());
          }});

    okButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            // When accepted the dialog is only hidden. Don't
            // dispose it here, because isDisplayable() is used
            // to find out that OK was pressed!
            _parametersDialog.setVisible(false);
          }});

    cancelButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            // When cancelled the dialog is disposed (which is
            // also done when the dialog is closed). It must be
            // disposed here, because isDisplayable() is used
            // to find out that the Dialog was cancelled!
            _parametersDialog.dispose();
            cleanUp();
          }});

    // Search models in currently selected model folder. This
    // populates the model combobox and sets _model if a model was
    // found. This should also implicitly update the tiling fields.
    _modelFolder = new File(
        Prefs.get("unet_segmentation.modelDefinitionFolder", "."));
    searchModels();

    // Set uncritical fields
    _useGPUComboBox.setSelectedItem(
        Prefs.get("unet_segmentation.gpuId", "none"));

    // Finalize the dialog
    _parametersDialog.pack();
    _parametersDialog.setMinimumSize(
        _parametersDialog.getPreferredSize());
    _parametersDialog.setMaximumSize(
        new Dimension(
            Integer.MAX_VALUE,
            _parametersDialog.getPreferredSize().height));
    _parametersDialog.setLocationRelativeTo(_imp.getWindow());

    // Free all resources and make isDisplayable() return false to
    // distinguish dialog close from accept
    _parametersDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
  }

  public boolean getParameters() {
    if (_imp == null) setImagePlus(WindowManager.getCurrentImage());
    if (_imp == null) {
      IJ.showMessage(
          "U-Net segmentation requires an open hyperstack to segment.");
      return false;
    }

    boolean dialogOK = false;
    while (!dialogOK) {
      dialogOK = true;
      _parametersDialog.setVisible(true);

      // Dialog was cancelled
      if (!_parametersDialog.isDisplayable()) {
        cleanUp();
        return false;
      }

      if (model() == null || model().file == null) {
        dialogOK = false;
        IJ.showMessage("Please select a model. Probably you first need " +
                       "to select a folder containing models.");
        continue;
      }

      if (!(model().nChannels == _imp.getNChannels() ||
            (model().nChannels == 3 &&
             (_imp.getType() == ImagePlus.COLOR_256 ||
              _imp.getType() == ImagePlus.COLOR_RGB)))) {
        dialogOK = false;
        IJ.showMessage("The selected model cannot segment " +
                       _imp.getNChannels() + "-channel images.");
        continue;
      }

      if (_weightsFileTextField.getText() == "") {
        dialogOK = false;
        IJ.showMessage(
            "Please enter the (remote) path to the trained weights.");
        continue;
      }

      try {
        _sshSession = _hostConfiguration.sshSession();
      }
      catch (JSchException e) {
        IJ.log("SSH connection to '" + _hostConfiguration.hostname() +
               "' failed.");
        IJ.showMessage(
            "Could not connect to remote host '" +
            _hostConfiguration.hostname() + "'\n" +
            "Please check your login credentials.\n" + e);
        dialogOK = false;
        continue;
      }

      // Check whether caffe binary exists and is executable
      ProcessResult res = null;
      if (_sshSession == null) {
        try {
          Vector<String> cmd = new Vector<String>();
          cmd.add(Prefs.get("unet_segmentation.caffeBinary", "caffe_unet"));
          res = UnetTools.execute(cmd, this);
        }
        catch (InterruptedException e) {
          cleanUp();
          return false;
        }
        catch (IOException e) {
          res.exitStatus = 1;
        }
      }
      else {
        try {
          String cmd = Prefs.get("unet_segmentation.caffeBinary", "caffe_unet");
          res = UnetTools.execute(cmd, _sshSession, this);
        }
        catch (InterruptedException e) {
          cleanUp();
          return false;
        }
        catch (JSchException e) {
          res.exitStatus = 1;
        }
        catch (IOException e) {
          res.exitStatus = 1;
        }
      }

      if (res.exitStatus != 0) {
        dialogOK = false;
        String caffePath = JOptionPane.showInputDialog(
            _imp.getWindow(), "caffe was not found.\n" +
            "Please specify your caffe binary\n",
            Prefs.get("unet_segmentation.caffeBinary", "caffe_unet"));
        if (caffePath == null) {
          cleanUp();
          return false;
        }
        if (caffePath.equals(""))
            Prefs.set("unet_segmentation.caffeBinary", "caffe_unet");
        else Prefs.set("unet_segmentation.caffeBinary", caffePath);
        continue;
      }

      // Check whether combination of model and weights can be used for
      // segmentation
      String gpuParm = null;
      String selectedGPU = (String)_useGPUComboBox.getSelectedItem();
      if (selectedGPU.contains("GPU "))
          gpuParm = "-gpu " + selectedGPU.substring(selectedGPU.length() - 1);
      else if (selectedGPU.contains("all")) gpuParm = "-gpu all";
      if (_sshSession != null) {
        model().remoteAbsolutePath =
            _processFolderTextField.getText() + "/" + _jobId + "_model.h5";
        try {
          _createdRemoteFolders.addAll(
              UnetTools.put(
                  model().file, model().remoteAbsolutePath, _sshSession, this));
          _createdRemoteFiles.add(model().remoteAbsolutePath);
        }
        catch (InterruptedException e) {
          cleanUp();
          return false;
        }
        catch (SftpException e) {
          dialogOK = false;
          IJ.showMessage(
              "Model upload failed.\nDo you have sufficient " +
              "permissions to create the processing folder on " +
              "the remote host?");
          continue;
        }
        catch (JSchException e) {
          dialogOK = false;
          IJ.showMessage(
              "Model upload failed.\nDo you have sufficient " +
              "permissions to create the processing folder on " +
              "the remote host?");
          continue;
        }
        catch (IOException e) {
          dialogOK = false;
          IJ.showMessage(
              "Model upload failed. Could not read model file.");
          continue;
        }

        try {
          do {
            String cmd =
                Prefs.get("unet_segmentation.caffeBinary", "caffe_unet") +
                " check_model_and_weights_h5 -model \"" +
                model().remoteAbsolutePath + "\" -weights \"" +
                _weightsFileTextField.getText() + "\" " + gpuParm;
            res = UnetTools.execute(cmd, _sshSession, this);
            if (res.exitStatus != 0) {
              int selectedOption = JOptionPane.showConfirmDialog(
                  _imp.getWindow(), "No trained weights found at the " +
                  "given location on the backend server.\nDo you want " +
                  "to upload the weights now?", "Upload weights?",
                  JOptionPane.YES_NO_CANCEL_OPTION,
                  JOptionPane.QUESTION_MESSAGE);
              switch (selectedOption) {
              case JOptionPane.YES_OPTION: {
                File startFile =
                    (model() == null || model().file == null ||
                     model().file.getParentFile() == null) ?
                    new File(".") : model().file.getParentFile();
                JFileChooser f = new JFileChooser(startFile);
                f.setDialogTitle("Select trained U-net weights");
                f.setFileFilter(
                    new FileNameExtensionFilter(
                        "HDF5 and prototxt files", "h5", "H5",
                        "prototxt", "PROTOTXT", "caffemodel", "CAFFEMODEL"));
                f.setMultiSelectionEnabled(false);
                f.setFileSelectionMode(JFileChooser.FILES_ONLY);
                int res2 = f.showDialog(_imp.getWindow(), "Select");
                if (res2 != JFileChooser.APPROVE_OPTION)
                    throw new InterruptedException("Aborted by user");
                try {
                  UnetTools.put(
                      f.getSelectedFile(), _weightsFileTextField.getText(),
                      _sshSession, this);
                }
                catch (SftpException e) {
                  res.exitStatus = 3;
                  res.shortErrorString =
                      "Upload failed.\nDo you have sufficient " +
                      "permissions to create a file at the given " +
                      "backend server path?";
                  res.cerr = e.getMessage();
                  break;
                }
                break;
              }
              case JOptionPane.NO_OPTION: {
                res.exitStatus = 2;
                res.shortErrorString = "Weight file selection required";
                res.cerr = "Weight file " +
                    _weightsFileTextField.getText() + " not found";
                break;
              }
              case JOptionPane.CANCEL_OPTION:
              case JOptionPane.CLOSED_OPTION:
                throw new InterruptedException("Aborted by user");
              }
              if (res.exitStatus > 1) break;
            }
          }
          while (res.exitStatus != 0);
        }
        catch (InterruptedException e) {
          cleanUp();
          return false;
        }
        catch (JSchException e) {
          res.exitStatus = 1;
          res.shortErrorString = "SSH connection error";
          res.cerr = e.getMessage();
        }
        catch (IOException e) {
          res.exitStatus = 1;
          res.shortErrorString = "Input/Output error";
          res.cerr = e.getMessage();
        }
      }
      else {
        try {
          Vector<String> cmd = new Vector<String>();
          cmd.add(Prefs.get("unet_segmentation.caffeBinary", "caffe_unet"));
          cmd.add("check_model_and_weights_h5");
          cmd.add("-model");
          cmd.add(model().file.getAbsolutePath());
          cmd.add("-weights");
          cmd.add(_weightsFileTextField.getText());
          if (gpuParm != null) {
            cmd.add(gpuParm.split(" ")[0]);
            cmd.add(gpuParm.split(" ")[1]);
          }
          res = UnetTools.execute(cmd, this);
        }
        catch (InterruptedException e) {
          cleanUp();
          return false;
        }
        catch (IOException e) {
          res.exitStatus = 1;
          res.shortErrorString = "Input/Output error";
          res.cerr = e.getMessage();
        }
      }
      if (res.exitStatus != 0) {
        dialogOK = false;

        // User decided to change weight file, so don't bother him with
        // additional message boxes
        if (res.exitStatus == 2) continue;

        IJ.log(res.cerr);
        IJ.showMessage(
            "Model/Weight check failed:\n" + res.shortErrorString);
        continue;
      }
    }

    _parametersDialog.dispose();

    // Save residual preferences
    Prefs.set("unet_segmentation.modelName", (String)model().name);
    model().savePreferences();
    Prefs.set("unet_segmentation.gpuId",
              (String)_useGPUComboBox.getSelectedItem());
    Prefs.set("unet_segmentation.useRemoteHost", _sshSession != null);
    Prefs.set("unet_segmentation.processfolder",
              _processFolderTextField.getText());
    Prefs.set("unet_segmentation.keepOriginal",
              _keepOriginalCheckBox.isSelected());
    Prefs.set("unet_segmentation.outputScores",
              _outputScoresCheckBox.isSelected());

    if (_jobTableModel != null) _jobTableModel.fireTableDataChanged();

    return true;
  }

  public void runUnetSegmentation(
      String fileName, Session session)
      throws JSchException, IOException, InterruptedException {
    _taskStatus.isIndeterminate = true;
    setTaskProgress("Initializing U-Net", 0, 0);
    String gpuParm = new String();
    String selectedGPU = (String)_useGPUComboBox.getSelectedItem();
    if (selectedGPU.contains("GPU "))
        gpuParm = "-gpu " + selectedGPU.substring(selectedGPU.length() - 1);
    else if (selectedGPU.contains("all")) gpuParm = "-gpu all";

    String nTilesParm = model().getCaffeTilingParameter();

    String commandString =
        Prefs.get("unet_segmentation.caffeBinary", "caffe_unet") +
        " tiled_predict -infileH5 \"" + fileName +
        "\" -outfileH5 \"" + fileName + "\" -model \"" +
        model().remoteAbsolutePath + "\" -weights \"" +
        _weightsFileTextField.getText() + "\" -iterations 0 " +
        nTilesParm + " " + gpuParm;

    IJ.log(commandString);

    Channel channel = session.openChannel("exec");
    ((ChannelExec)channel).setCommand(commandString);

    InputStream stdError = ((ChannelExec)channel).getErrStream();
    InputStream stdOutput = channel.getInputStream();

    channel.connect();

    byte[] buf = new byte[1024];
    String errorMsg = new String();
    String outMsg = new String();
    int exitStatus = -1;
    try {
      while (true) {
        while(stdOutput.available() > 0) {
          int i = stdOutput.read(buf, 0, 1024);
          if (i < 0) break;
          outMsg += "\n" + new String(buf, 0, i);
        }
        while(stdError.available() > 0) {
          int i = stdError.read(buf, 0, 1024);
          if (i < 0) break;
          errorMsg += "\n" + new String(buf, 0, i);
        }
        int idx = -1;
        while ((idx = outMsg.indexOf('\n')) != -1) {
          String line = outMsg.substring(0, idx);
          outMsg = outMsg.substring(idx + 1);
          if (line.regionMatches(0, "Processing batch ", 0, 17)) {
            line = line.substring(17);
            int sepPos = line.indexOf('/');
            int batchIdx = Integer.parseInt(line.substring(0, sepPos));
            line = line.substring(sepPos + 1);
            sepPos = line.indexOf(',');
            int nBatches = Integer.parseInt(line.substring(0, sepPos));
            line = line.substring(sepPos + 7);
            sepPos = line.indexOf('/');
            int tileIdx = Integer.parseInt(line.substring(0, sepPos));
            line = line.substring(sepPos + 1);
            int nTiles = Integer.parseInt(line);
            setTaskProgress(
                "Segmenting batch " + String.valueOf(batchIdx) + "/" +
                String.valueOf(nBatches) + ", tile " +
                String.valueOf(tileIdx) + "/" + String.valueOf(nTiles),
                (batchIdx - 1) * nTiles + tileIdx - 1, nBatches * nTiles);
            setProgress(
                (int) (_taskProgressMin +
                       (float) ((batchIdx - 1) * nTiles + tileIdx - 1) /
                       (float) (nBatches * nTiles) *
                       (_taskProgressMax - _taskProgressMin)));
          }
        }
        if (channel.isClosed()) {
          if(stdOutput.available() > 0 || stdError.available() > 0) continue;
          exitStatus = channel.getExitStatus();
          break;
        }
        if (interrupted()) throw new InterruptedException();
        Thread.sleep(100);
      }
    }
    catch (InterruptedException e) {
      _readyCancelButton.setText("Terminating...");
      _readyCancelButton.setEnabled(false);
      try {
        channel.sendSignal("TERM");
        int graceMilliSeconds = 10000;
        int timeElapsedMilliSeconds = 0;
        while (!channel.isClosed() &&
               timeElapsedMilliSeconds <= graceMilliSeconds) {
          timeElapsedMilliSeconds += 100;
          try {
            Thread.sleep(100);
          }
          catch (InterruptedException eInner) {}
        }
        if (!channel.isClosed()) channel.sendSignal("KILL");
      }
      catch (Exception eInner) {
        IJ.log(
            "Process could not be terminated using SIGTERM: " + eInner);
      }
      channel.disconnect();
      throw e;
    }
    channel.disconnect();

    if (exitStatus != 0) {
      IJ.log(errorMsg);
      throw new IOException(
          "Error during segmentation: exit status " + exitStatus +
          "\nSee log for further details");
    }
  }

  public void runUnetSegmentation(File file)
      throws IOException, InterruptedException {
    setTaskProgress("Initializing U-Net", 0, 0);
    String gpuAttribute = new String();
    String gpuValue = new String();
    String selectedGPU = (String)_useGPUComboBox.getSelectedItem();
    if (selectedGPU.contains("GPU ")) {
      gpuAttribute = "-gpu";
      gpuValue = selectedGPU.substring(selectedGPU.length() - 1);
    }
    else if (selectedGPU.contains("all")) {
      gpuAttribute = "-gpu";
      gpuValue = "all";
    }

    String[] parameters = model().getCaffeTilingParameter().split("\\s");
    String nTilesAttribute = parameters[0];
    String nTilesValue = parameters[1];

    String commandString =
        Prefs.get("unet_segmentation.caffeBinary", "caffe_unet");

    IJ.log(
        commandString + " tiled_predict -infileH5 \"" +
        file.getAbsolutePath() + "\" -outfileH5 \"" +
        file.getAbsolutePath() + "\" -model \"" +
        model().file.getAbsolutePath() + "\" -weights \"" +
        _weightsFileTextField.getText() + "\" -iterations 0 " +
        nTilesAttribute + " " + nTilesValue + " " + gpuAttribute + " " +
        gpuValue);
    ProcessBuilder pb;
    if (!gpuAttribute.equals(""))
        pb = new ProcessBuilder(
            commandString, "tiled_predict", "-infileH5",
            file.getAbsolutePath(), "-outfileH5", file.getAbsolutePath(),
            "-model", model().file.getAbsolutePath(), "-weights",
            _weightsFileTextField.getText(), "-iterations", "0",
            nTilesAttribute, nTilesValue, gpuAttribute, gpuValue);
    else
        pb = new ProcessBuilder(
            commandString, "tiled_predict", "-infileH5",
            file.getAbsolutePath(), "-outfileH5", file.getAbsolutePath(),
            "-model", model().file.getAbsolutePath(), "-weights",
            _weightsFileTextField.getText(), "-iterations", "0",
            nTilesAttribute, nTilesValue);

    Process p = pb.start();

    BufferedReader stdOutput =
        new BufferedReader(new InputStreamReader(p.getInputStream()));
    BufferedReader stdError =
        new BufferedReader(new InputStreamReader(p.getErrorStream()));

    int exitStatus = -1;
    String line;
    String errorMsg = "";
    try {
      while (true) {
        // Check for ready() to avoid thread blocking, then read
        // all available lines from the buffer and update progress
        while (stdOutput.ready()) {
          line = stdOutput.readLine();
          if (line.regionMatches(0, "Processing batch ", 0, 17)) {
            line = line.substring(17);
            int sepPos = line.indexOf('/');
            int batchIdx = Integer.parseInt(line.substring(0, sepPos));
            line = line.substring(sepPos + 1);
            sepPos = line.indexOf(',');
            int nBatches = Integer.parseInt(line.substring(0, sepPos));
            line = line.substring(sepPos + 7);
            sepPos = line.indexOf('/');
            int tileIdx = Integer.parseInt(line.substring(0, sepPos));
            line = line.substring(sepPos + 1);
            int nTiles = Integer.parseInt(line);
            setTaskProgress(
                "Segmenting batch " + String.valueOf(batchIdx) + "/" +
                String.valueOf(nBatches) + ", tile " +
                String.valueOf(tileIdx) + "/" + String.valueOf(nTiles),
                (batchIdx - 1) * nTiles + tileIdx - 1, nBatches * nTiles);
            setProgress(
                (int) (_taskProgressMin +
                       (float) ((batchIdx - 1) * nTiles + tileIdx - 1) /
                       (float) (nBatches * nTiles) *
                       (_taskProgressMax - _taskProgressMin)));
          }
        }
        // Also read error stream to avoid stream overflow that leads
        // to process stalling
        while (stdError.ready()) {
          line = stdError.readLine();
          errorMsg += line + "\n";
        }

        try {
          exitStatus = p.exitValue();
          break;
        }
        catch (IllegalThreadStateException e) {}
        if (interrupted()) throw new InterruptedException();
        Thread.sleep(100);
      }
    }
    catch (InterruptedException e) {
      _readyCancelButton.setText("Terminating...");
      _readyCancelButton.setEnabled(false);
      p.destroy();
      throw e;
    }

    if (exitStatus != 0) {
      IJ.log(errorMsg);
      throw new IOException(
          "Error during segmentation: exit status " + exitStatus +
          "\nSee log for further details");
    }

    setTaskProgress(1, 1);
  }

  public static void segmentHyperStack(String params)
      throws InterruptedException {
    final UnetJob job = new UnetJob();
    if (job._imp == null)
        job.setImagePlus(WindowManager.getCurrentImage());
    if (job._imp == null) {
      IJ.error(
          "U-Net Segmentation", "No image selected for segmentation.");
      return;
    }

    String[] parameterStrings = params.split(",");
    Map<String,String> parameters = new HashMap<String,String>();
    for (int i = 0; i < parameterStrings.length; i++)
        parameters.put(parameterStrings[i].split("=")[0],
                       parameterStrings[i].split("=")[1]);
    job._modelComboBox.removeAllItems();
    ModelDefinition model = new ModelDefinition();
    job._modelComboBox.addItem(model);
    job._modelComboBox.setSelectedItem(model);
    job.model().load(new File(parameters.get("modelFilename")));
    job._weightsFileTextField.setText(parameters.get("weightsFilename"));
    job.model().setFromTilingParameterString(parameterStrings[2]);
    job._useGPUComboBox.setSelectedItem(parameters.get("gpuId"));
    if (Boolean.valueOf(parameters.get("useRemoteHost"))) {
      try {
        String hostname = parameters.get("hostname");
        int port = Integer.valueOf(parameters.get("port"));
        String username = parameters.get("username");
        JSch jsch = new JSch();
        jsch.setKnownHosts(
            new File(System.getProperty("user.home") +
                     "/.ssh/known_hosts").getAbsolutePath());
        if (parameters.containsKey("RSAKeyfile"))
            jsch.addIdentity(parameters.get("RSAKeyfile"));
        job._sshSession = jsch.getSession(username, hostname, port);
        job._sshSession.setUserInfo(new MyUserInfo());

        if (!parameters.containsKey("RSAKeyfile")) {
          final JDialog passwordDialog = new JDialog(
              job._imp.getWindow(), "U-Net Segmentation", true);
          JPanel mainPanel = new JPanel();
          mainPanel.add(new JLabel("Password:"));
          final JPasswordField passwordField = new JPasswordField(15);
          mainPanel.add(passwordField);
          passwordDialog.add(mainPanel, BorderLayout.CENTER);
          JButton okButton = new JButton("OK");
          okButton.addActionListener(
              new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                  char[] password = passwordField.getPassword();
                  byte[] passwordAsBytes =
                      HostConfigurationPanel.toBytes(password);
                  job._sshSession.setPassword(passwordAsBytes);
                  Arrays.fill(passwordAsBytes, (byte) 0);
                  Arrays.fill(password, '\u0000');
                  passwordField.setText("");
                  passwordDialog.dispose();
                }});
          passwordDialog.add(okButton, BorderLayout.SOUTH);
          passwordDialog.getRootPane().setDefaultButton(okButton);
          passwordDialog.pack();
          passwordDialog.setMinimumSize(passwordDialog.getPreferredSize());
          passwordDialog.setMaximumSize(passwordDialog.getPreferredSize());
          passwordDialog.setLocationRelativeTo(job._imp.getWindow());
          passwordDialog.setVisible(true);
        }

        job._sshSession.connect();
      }
      catch (JSchException e) {
        IJ.log("Macro call to UnetJob.segmentHyperStack aborted. " +
               "Could not establish SSH connection.");
        IJ.error("UnetJob.segmentHyperStack",
                 "Could not establish SSH connection.");
        return;
      }
    }
    job._processFolderTextField.setText(parameters.get("processFolder"));
    job._keepOriginalCheckBox.setSelected(
        Boolean.valueOf(parameters.get("keepOriginal")));
    job._outputScoresCheckBox.setSelected(
        Boolean.valueOf(parameters.get("outputScores")));
    job._isInteractive = false;

    job.start();
    job.join();
    job.finish();
  }

  @Override
  public void run(String arg) {
    setImagePlus(WindowManager.getCurrentImage());
    if (_imp == null) {
      IJ.error(
          "U-Net Segmentation", "No image selected for segmentation.");
      return;
    }
    prepareParametersDialog();
    try {
      start();
      join();
      finish();
    }
    catch (InterruptedException e) {}
    IJ.showProgress(1.0);
  }

  @Override
  public void run() {
    setProgress(0);
    if (_isInteractive && !getParameters()) return;
    try {
      if (_sshSession != null) {
        if (!_isInteractive) {
          model().remoteAbsolutePath =
              _processFolderTextField.getText() + "/" + _jobId + "_model.h5";
          _createdRemoteFolders.addAll(
              UnetTools.put(
                  model().file, model().remoteAbsolutePath, _sshSession, this));
          _createdRemoteFiles.add(model().remoteAbsolutePath);
        }
        setProgress(1);

        _localTmpFile = File.createTempFile(_jobId, ".h5");
        _localTmpFile.delete();

        String remoteFileName = _processFolderTextField.getText() + "/" +
            _jobId + ".h5";

        setImagePlus(
            UnetTools.saveHDF5Blob(
                _imp, _localTmpFile, this, _keepOriginalCheckBox.isSelected()));
        _impShape = _imp.getDimensions();
        if (interrupted()) throw new InterruptedException();
        setProgress(2);

        setTaskProgressRange(3, 10);
        _createdRemoteFolders.addAll(
            UnetTools.put(_localTmpFile, remoteFileName, _sshSession, this));
        _createdRemoteFiles.add(remoteFileName);
        if (interrupted()) throw new InterruptedException();
        setProgress(10);

        setTaskProgressRange(11, 90);
        runUnetSegmentation(remoteFileName, _sshSession);
        if (interrupted()) throw new InterruptedException();
        setProgress(90);

        setTaskProgressRange(91, 100);
        UnetTools.get(remoteFileName, _localTmpFile, _sshSession, this);
        if (interrupted()) throw new InterruptedException();
        setProgress(100);
      }
      else {
        _localTmpFile = new File(
            _processFolderTextField.getText() + "/" + _jobId + ".h5");

        setImagePlus(
            UnetTools.saveHDF5Blob(
                _imp, _localTmpFile, this, _keepOriginalCheckBox.isSelected()));
        _impShape = _imp.getDimensions();
        if (interrupted()) throw new InterruptedException();
        setProgress(2);

        setTaskProgressRange(3, 100);
        runUnetSegmentation(_localTmpFile);
        if (interrupted()) throw new InterruptedException();
        setProgress(100);
      }
      setReady(true);
    }
    catch (InterruptedException e) {
      IJ.showMessage("Job " + _jobId + " canceled. Cleaning up.");
      cleanUp();
      if (_jobTableModel != null) _jobTableModel.deleteJob(this);
    }
    catch (Exception e) {
      IJ.error(e.toString());
      cleanUp();
      if (_jobTableModel != null) _jobTableModel.deleteJob(this);
    }
  }

  public void cleanUp() {
    for (int i = 0; i < _createdRemoteFiles.size(); i++) {
      try {
        UnetTools.removeFile(
            _createdRemoteFiles.get(i), _sshSession, this);
      }
      catch (Exception e) {
        IJ.log("Could not remove temporary file " +
               _createdRemoteFiles.get(i) + ": " + e);
      }
    }
    for (int i = 0; i < _createdRemoteFolders.size(); i++) {
      try {
        UnetTools.removeFolder(
            _createdRemoteFolders.get(i), _sshSession, this);
      }
      catch (Exception e) {
        IJ.log("Could not remove temporary folder " +
               _createdRemoteFolders.get(i) + ": " + e);
      }
    }
    if (_sshSession != null) _sshSession.disconnect();
    _finished = true;
    if (_jobTableModel != null) _jobTableModel.deleteJob(this);
    IJ.log("Unet_Segmentation finished");
  }

};
