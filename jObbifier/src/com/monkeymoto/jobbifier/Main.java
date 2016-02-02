package com.monkeymoto.jobbifier;

import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

import javax.swing.ButtonGroup;
import javax.swing.GroupLayout;
import javax.swing.GroupLayout.Alignment;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.LayoutStyle.ComponentPlacement;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.JScrollPane;

public class Main {

	private JFrame frmJObbifier;
	private File inputDir;
	private File outputDir;
	private JLabel lblInputFolder;
	private JLabel lblOutputFolder;
	private JLabel lblOutputFileName;
	private JLabel lblPackageName;
	private JTextField txtPackageName;
	private JLabel lblPackageVersion;
	private JTextPane txtpnOutput;
	private JRadioButton rdbtnMain;
	private JRadioButton rdbtnPatch;
	private JSpinner spinner;
	private JButton btnInputSelection;
	private JButton btnOutputSelection;
	private JButton btnCreateObb;
	private JScrollPane scrollPane;

	/**
	 * Launch the application.
	 */
	public static void main(String[] args) {
		EventQueue.invokeLater(new Runnable() {
			public void run() {
				try {
					Main window = new Main();
					window.frmJObbifier.setVisible(true);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}

	/**
	 * Create the application.
	 */
	public Main() {
		initialize();
	}
	
	private String getOutputFileNamePrefix()
	{
		return "Output file name: ";
	}
	
	private String getOutputFileName()
	{
		return (rdbtnMain.isSelected() ? "main" : "patch") + "." + spinner.getValue().toString() + "." + txtPackageName.getText() + ".obb";
	}
	
	private void updateOutputFileNameLabel()
	{
		lblOutputFileName.setText(getOutputFileNamePrefix() + getOutputFileName());
	}

	/**
	 * Initialize the contents of the frame.
	 */
	private void initialize() {
		frmJObbifier = new JFrame();
		frmJObbifier.setBounds(100, 100, 640, 360);
		frmJObbifier.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frmJObbifier.setTitle("jObbifier");
		inputDir = null;
		outputDir = null;

		JMenuBar menuBar = new JMenuBar();
		frmJObbifier.setJMenuBar(menuBar);

		JMenu mnFile = new JMenu("File");
		menuBar.add(mnFile);
		
		lblInputFolder = new JLabel("Input folder: (None)");
		
		btnInputSelection = new JButton("...");
		btnInputSelection.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				JFileChooser chooser = new JFileChooser();
				chooser.setCurrentDirectory(new File(inputDir == null ? "." : inputDir.getPath()));
				chooser.setDialogTitle("Select input folder...");
				chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
				chooser.setAcceptAllFileFilterUsed(false);
				if (chooser.showOpenDialog(frmJObbifier) == JFileChooser.APPROVE_OPTION) {
					inputDir = chooser.getSelectedFile();
					lblInputFolder.setText("Input folder: " + inputDir.getPath());
				}
			}
		});
		
		lblOutputFolder = new JLabel("Output location: (None)");
		
		btnOutputSelection = new JButton("...");
		btnOutputSelection.addActionListener(new ActionListener()
		{
			public void actionPerformed(ActionEvent e)
			{
				JFileChooser chooser = new JFileChooser();
				chooser.setCurrentDirectory(new File(outputDir == null ? "." : outputDir.getPath()));
				chooser.setDialogTitle("Select output folder...");
				chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
				chooser.setAcceptAllFileFilterUsed(false);
				if (chooser.showOpenDialog(frmJObbifier) == JFileChooser.APPROVE_OPTION)
				{
					outputDir = chooser.getSelectedFile();
					lblOutputFolder.setText("Output folder: " + outputDir.getPath());
				}
			}
		});
		
		lblPackageName = new JLabel("Package name:");
		
		txtPackageName = new JTextField();
		txtPackageName.setText("com.example.sample");
		txtPackageName.setColumns(35);
		txtPackageName.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void changedUpdate(DocumentEvent e)
			{
				updateOutputFileNameLabel();
			}

			@Override
			public void insertUpdate(DocumentEvent e)
			{
				updateOutputFileNameLabel();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				updateOutputFileNameLabel();
			}
		});
		
		lblPackageVersion = new JLabel("Package version:");
		
		spinner = new JSpinner();
		spinner.addChangeListener(new ChangeListener() {
			public void stateChanged(ChangeEvent e) {
				updateOutputFileNameLabel();
			}
		});
		spinner.setModel(new SpinnerNumberModel(new Integer(1), new Integer(1), null, new Integer(1)));
		
		rdbtnMain = new JRadioButton("Main");
		rdbtnMain.setSelected(true);
		rdbtnMain.addItemListener(new ItemListener()
		{
			@Override
			public void itemStateChanged(ItemEvent e)
			{
				updateOutputFileNameLabel();
			}
		});
		
		rdbtnPatch = new JRadioButton("Patch");
		rdbtnPatch.addItemListener(new ItemListener()
		{
			@Override
			public void itemStateChanged(ItemEvent e)
			{
				updateOutputFileNameLabel();
			}
		});
		
		lblOutputFileName = new JLabel();
		updateOutputFileNameLabel();
		
		btnCreateObb = new JButton("Create OBB");
		btnCreateObb.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if (inputDir == null)
				{
					System.out.println("Error: You must select an input directory first!");
					return;
				}
				if (outputDir == null)
				{
					System.out.println("Error: You must select an output directory first!");
					return;
				}
				if (inputDir == outputDir)
				{
					System.out.println("Error: Input and output directories cannot be the same.");
					return;
				}
				String[] args = new String[]
				{
					"-d", inputDir.getPath(),
					"-o", outputDir.getPath() + File.separator + getOutputFileName(),
					"-pn", lblPackageName.getText(),
					"-pv", spinner.getValue().toString(),
					"-v"
				};
				com.android.jobb.Main.main(args);
			}
		});
		
		scrollPane = new JScrollPane();
		scrollPane.setAutoscrolls(true);
		GroupLayout groupLayout = new GroupLayout(frmJObbifier.getContentPane());
		groupLayout.setHorizontalGroup(
			groupLayout.createParallelGroup(Alignment.LEADING)
				.addGroup(groupLayout.createSequentialGroup()
					.addGap(7)
					.addGroup(groupLayout.createParallelGroup(Alignment.LEADING)
						.addGroup(groupLayout.createSequentialGroup()
							.addComponent(scrollPane, GroupLayout.DEFAULT_SIZE, 605, Short.MAX_VALUE)
							.addContainerGap())
						.addGroup(groupLayout.createSequentialGroup()
							.addGroup(groupLayout.createParallelGroup(Alignment.LEADING)
								.addGroup(groupLayout.createSequentialGroup()
									.addGroup(groupLayout.createParallelGroup(Alignment.LEADING)
										.addComponent(btnInputSelection)
										.addComponent(btnOutputSelection))
									.addPreferredGap(ComponentPlacement.RELATED)
									.addGroup(groupLayout.createParallelGroup(Alignment.LEADING)
										.addComponent(lblOutputFolder)
										.addComponent(lblInputFolder)))
								.addGroup(groupLayout.createSequentialGroup()
									.addComponent(lblPackageName)
									.addPreferredGap(ComponentPlacement.RELATED)
									.addComponent(txtPackageName, GroupLayout.PREFERRED_SIZE, 251, GroupLayout.PREFERRED_SIZE)
									.addGap(12)
									.addComponent(lblPackageVersion)
									.addPreferredGap(ComponentPlacement.RELATED)
									.addComponent(spinner, GroupLayout.PREFERRED_SIZE, 77, GroupLayout.PREFERRED_SIZE)))
							.addContainerGap(67, Short.MAX_VALUE))
						.addGroup(groupLayout.createSequentialGroup()
							.addComponent(btnCreateObb)
							.addPreferredGap(ComponentPlacement.UNRELATED)
							.addComponent(rdbtnMain)
							.addPreferredGap(ComponentPlacement.RELATED)
							.addComponent(rdbtnPatch)
							.addPreferredGap(ComponentPlacement.RELATED)
							.addComponent(lblOutputFileName)
							.addContainerGap())))
		);
		groupLayout.setVerticalGroup(
			groupLayout.createParallelGroup(Alignment.LEADING)
				.addGroup(groupLayout.createSequentialGroup()
					.addGap(11)
					.addGroup(groupLayout.createParallelGroup(Alignment.BASELINE)
						.addComponent(btnInputSelection)
						.addComponent(lblInputFolder))
					.addPreferredGap(ComponentPlacement.RELATED, GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
					.addGroup(groupLayout.createParallelGroup(Alignment.BASELINE)
						.addComponent(btnOutputSelection)
						.addComponent(lblOutputFolder))
					.addGroup(groupLayout.createParallelGroup(Alignment.LEADING)
						.addGroup(groupLayout.createSequentialGroup()
							.addGap(12)
							.addComponent(lblPackageName))
						.addGroup(groupLayout.createSequentialGroup()
							.addGap(9)
							.addGroup(groupLayout.createParallelGroup(Alignment.BASELINE)
								.addComponent(txtPackageName, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
								.addComponent(lblPackageVersion)
								.addComponent(spinner, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE))))
					.addPreferredGap(ComponentPlacement.RELATED)
					.addGroup(groupLayout.createParallelGroup(Alignment.BASELINE)
						.addComponent(btnCreateObb)
						.addComponent(rdbtnMain)
						.addComponent(rdbtnPatch)
						.addComponent(lblOutputFileName))
					.addPreferredGap(ComponentPlacement.RELATED)
					.addComponent(scrollPane, GroupLayout.PREFERRED_SIZE, 140, GroupLayout.PREFERRED_SIZE)
					.addContainerGap())
		);
		
		txtpnOutput = new JTextPane();
		scrollPane.setViewportView(txtpnOutput);
		txtpnOutput.setEditable(false);
		ButtonGroup mainPatchGroup = new ButtonGroup();
		mainPatchGroup.add(rdbtnMain);
		mainPatchGroup.add(rdbtnPatch);
		frmJObbifier.getContentPane().setLayout(groupLayout);
		redirectSystemStreams();
	}
	
	private void updateTextPane(final String text)
	{
		SwingUtilities.invokeLater(new Runnable()
		{
			public void run()
			{
				Document doc = txtpnOutput.getDocument();
				try
				{
					doc.insertString(doc.getLength(), text, null);
				}
				catch (BadLocationException e)
				{
					throw new RuntimeException(e);
				}
				txtpnOutput.setCaretPosition(doc.getLength() - 1);
			}
		});
	}
	
	private void redirectSystemStreams()
	{
		OutputStream out = new OutputStream()
		{
			@Override
			public void write(final int b) throws IOException
			{
				updateTextPane(String.valueOf((char)b));
			}
			
			@Override
			public void write(byte[] b, int off, int len) throws IOException
			{
				updateTextPane(new String(b, off, len));
			}
			
			@Override
			public void write(byte[] b) throws IOException
			{
				write(b, 0, b.length);
			}
		};
		System.setOut(new PrintStream(out, true));
		System.setErr(new PrintStream(out, true));
	}
}
