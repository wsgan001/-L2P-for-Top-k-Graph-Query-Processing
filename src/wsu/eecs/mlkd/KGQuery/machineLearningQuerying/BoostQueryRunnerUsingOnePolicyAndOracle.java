package wsu.eecs.mlkd.KGQuery.machineLearningQuerying;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;

import biz.k11i.xgboost.Predictor;
import wsu.eecs.mlkd.KGQuery.TopKQuery.CacheServer;
import wsu.eecs.mlkd.KGQuery.TopKQuery.Dummy;
import wsu.eecs.mlkd.KGQuery.TopKQuery.Levenshtein;
import wsu.eecs.mlkd.KGQuery.TopKQuery.NeighborIndexing;
import wsu.eecs.mlkd.KGQuery.TopKQuery.PreProcessingLabels;
import wsu.eecs.mlkd.KGQuery.TopKQuery.Dummy.DummyFunctions;
import wsu.eecs.mlkd.KGQuery.TopKQuery.Dummy.DummyProperties;
import weka.classifiers.Classifier;

public class BoostQueryRunnerUsingOnePolicyAndOracle {

	private String MODELGRAPH_DB_PATH = "";
	private String PATTERNGRAPH_DB_PATH = "";

	private String queryFileName = "";
	private String queryFileDirectory = "";

	private String GName = ""; // Yago, DBPedia, ...

	private String queryDBInNeo4j = "query.db";
	private String GDirectory = "";
	private int numberOfSameExperiment = 2;
	private int k = 10;
	private GraphDatabaseService knowledgeGraph;
	private Levenshtein levenshtein;
	private NeighborIndexing neighborIndexingInstance;
	private CacheServer cacheServer;
	private int startingQueryIndex;
	private int endingQueryIndex = 1000000;
	private CommonFunctions commonFunctions = new CommonFunctions();
	private int validationFoldStartFrom;
	private int validationFoldEndTo;
	private String queriesFoldPath;
	private int modelIndex = 0;
	private String oracleSequenceFile;

	final int SELECTION_FEATURES_SIZE = 108;
	final int EXPANSION_FEATURES_SIZE = 49;

	float[] selectionNormalizationFeaturesVector;
	float[] expansionNormalizationFeaturesVector;

	public BoostQueryRunnerUsingOnePolicyAndOracle(String queryFileDirectory, String GDirectory, String GName,
			int startingQueryIndex, int endingQueryIndex, String PATTERNGRAPH_DB_PATH, int k, Levenshtein levenshtein) {
		this.queryFileDirectory = queryFileDirectory;
		this.GDirectory = GDirectory;
		this.GName = GName;
		this.startingQueryIndex = startingQueryIndex;
		this.endingQueryIndex = endingQueryIndex;
		this.PATTERNGRAPH_DB_PATH = PATTERNGRAPH_DB_PATH;
		this.k = k;
		this.levenshtein = levenshtein;
	}

	public BoostQueryRunnerUsingOnePolicyAndOracle() {

	}

	public static void main(String[] args) throws Exception {
		BoostQueryRunnerUsingOnePolicyAndOracle mlqrOverValidation = new BoostQueryRunnerUsingOnePolicyAndOracle();
		mlqrOverValidation.initialize(args);
	}

	public void initialize(String[] args) throws Exception {
		int numberOfPrefixChars = 0;
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("-queryFileName")) {
				queryFileName = args[++i];
				queryFileName = queryFileName.replace(".txt", "");
			} else if (args[i].equals("-queryFileDirectory")) {
				queryFileDirectory = args[++i];
				if (!queryFileDirectory.endsWith("/") && !queryFileDirectory.equals("")) {
					queryFileDirectory += "/";
				}
			} else if (args[i].equals("-GName")) {
				GName = args[++i];

			} else if (args[i].equals("-GDirectory")) {
				GDirectory = args[++i];

			} else if (args[i].equals("-similarityThreshold")) {
				DummyProperties.similarityThreshold = Float.parseFloat(args[++i]);
			} else if (args[i].equals("-numberOfSameExperiment")) {
				numberOfSameExperiment = Integer.parseInt(args[++i]);
			} else if (args[i].equals("-k")) {
				k = Integer.parseInt(args[++i]);
			} else if (args[i].equals("-numberOfPrefixChars")) {
				numberOfPrefixChars = Integer.parseInt(args[++i]);
			} else if (args[i].equals("-startingQueryIndex")) {
				startingQueryIndex = Integer.parseInt(args[++i]);
			} else if (args[i].equals("-endingQueryIndex")) {
				endingQueryIndex = Integer.parseInt(args[++i]);
			} else if (args[i].equals("-validationFoldStartFrom")) {
				validationFoldStartFrom = Integer.parseInt(args[++i]);
			} else if (args[i].equals("-validationFoldEndTo")) {
				validationFoldEndTo = Integer.parseInt(args[++i]);
			} else if (args[i].equals("-queriesFoldPath")) {
				queriesFoldPath = args[++i];
			} else if (args[i].equals("-maxNumberOfIteration")) {
				Integer.parseInt(args[++i]);
			} else if (args[i].equals("-modelIndex")) {
				modelIndex = Integer.parseInt(args[++i]);
			} else if (args[i].equals("-oracleSequenceFile")) {
				oracleSequenceFile = args[++i];

			}
		}

		cacheServer = new CacheServer();

		if (numberOfPrefixChars > 0) {
			DummyProperties.numberOfPrefixChars = numberOfPrefixChars;
		}
		if (!GDirectory.endsWith("/")) {
			GDirectory += "/";
		}
		MODELGRAPH_DB_PATH = GDirectory + GName;
		PATTERNGRAPH_DB_PATH = queryFileDirectory + queryDBInNeo4j;

		String totalParams = "";
		for (String arg : args) {
			totalParams += arg + ", ";
		}
		DummyFunctions.printIfItIsInDebuggedMode(totalParams);

		knowledgeGraph = new GraphDatabaseFactory().newEmbeddedDatabaseBuilder(MODELGRAPH_DB_PATH)
				.setConfig(GraphDatabaseSettings.pagecache_memory, "6g").newGraphDatabase();
		DummyFunctions.printIfItIsInDebuggedMode("after initialization of GraphDatabaseServices");

		commonFunctions.registerShutdownHook(knowledgeGraph);

		long start_time, end_time;
		double difference;

		HashMap<String, HashSet<Long>> nodeLabelsIndex = PreProcessingLabels.getPrefixLabelsIndex(knowledgeGraph,
				Dummy.DummyProperties.numberOfPrefixChars);

		neighborIndexingInstance = new NeighborIndexing();
		start_time = System.nanoTime();
		neighborIndexingInstance.knowledgeGraphNeighborIndexer(knowledgeGraph);
		end_time = System.nanoTime();
		difference = (end_time - start_time) / 1e6;
		System.out.println("knowledgeGraphNeighborIndexer finished in " + difference + "miliseconds!");

		levenshtein = new Levenshtein(nodeLabelsIndex, Dummy.DummyProperties.numberOfPrefixChars);

		HashSet<Integer> validationQueriesSet = commonFunctions.readQueryIndexBasedOnFolds(queriesFoldPath,
				validationFoldStartFrom, validationFoldEndTo);

		BoostingQueryRunnerForOnePolicyAndOracle bstQueryRunner = new BoostingQueryRunnerForOnePolicyAndOracle(knowledgeGraph,
				queryFileDirectory, GDirectory, GName, startingQueryIndex, endingQueryIndex, PATTERNGRAPH_DB_PATH, k,
				levenshtein, cacheServer, neighborIndexingInstance);

		ExactImitation ei = new ExactImitation();

		fillNormVectorsIfTheyAreExist();

		if (selectionNormalizationFeaturesVector != null) {
			System.out.println("selectionNormalizationFeaturesVector!=null");
		}
		if (expansionNormalizationFeaturesVector != null) {
			System.out.println("expansionNormalizationFeaturesVector!=null");
		}

		bstQueryRunner.findSpeedUpAndQualityOutOfAClassifierRegressorForASet(validationQueriesSet,
				new Predictor(new java.io.FileInputStream("BoosterClassifier_" + modelIndex + ".model")), null,
				numberOfSameExperiment,
				GName + "WithoutExpansionPolicy_WithOracle_‌BstRunningTime_" + modelIndex + "_" + modelIndex + ".txt",
				oracleSequenceFile, selectionNormalizationFeaturesVector, expansionNormalizationFeaturesVector);

		bstQueryRunner.findSpeedUpAndQualityOutOfAClassifierRegressorForASet(validationQueriesSet, null,
				new Predictor(new java.io.FileInputStream("BoosterRegressor_" + modelIndex + ".model")),
				numberOfSameExperiment,
				GName + "WithoutSelectionPolicy_WithOracle_BstRunningTime_" + modelIndex + "_" + modelIndex + ".txt",
				oracleSequenceFile, selectionNormalizationFeaturesVector, expansionNormalizationFeaturesVector);

		knowledgeGraph.shutdown();

		System.out.println("program is finished properly!");

	}

	private void fillNormVectorsIfTheyAreExist() throws Exception {
		File f = new File("normalizationVectors.txt");
		if (!f.exists()) {
			System.out.println("normalizationVectors.txt doesn't exist");
			return;
		}

		selectionNormalizationFeaturesVector = new float[SELECTION_FEATURES_SIZE];
		expansionNormalizationFeaturesVector = new float[EXPANSION_FEATURES_SIZE];

		FileInputStream fis = new FileInputStream(f.getAbsolutePath());
		BufferedReader br = new BufferedReader(new InputStreamReader(fis));

		String selectionNormalizationLine = null;
		String expansionNormalizationLine = null;

		selectionNormalizationLine = br.readLine();
		String[] splitedSelNorm = selectionNormalizationLine.split(",");
		for (int i = 0; i < SELECTION_FEATURES_SIZE; i++) {
			selectionNormalizationFeaturesVector[i] = Float.parseFloat(splitedSelNorm[i]);
		}

		expansionNormalizationLine = br.readLine();
		String[] splitedRegNorm = expansionNormalizationLine.split(",");
		for (int i = 0; i < EXPANSION_FEATURES_SIZE; i++) {
			expansionNormalizationFeaturesVector[i] = Float.parseFloat(splitedRegNorm[i]);
		}

		br.close();
	}

}
