package com.distributedsystems.recommendationsystems.Server;

import com.distributedsystems.recommendationsystems.Abstractions.IMaster;
import com.distributedsystems.recommendationsystems.Models.CommunicationMessage;
import com.distributedsystems.recommendationsystems.Models.MessageType;
import com.distributedsystems.recommendationsystems.Utils.MatrixHelpers;
import com.distributedsystems.recommendationsystems.Utils.ParserUtils;
import com.google.gson.Gson;
import com.distributedsystems.recommendationsystems.Models.Poi;
import org.gavaghan.geodesy.Ellipsoid;
import org.gavaghan.geodesy.GeodeticCalculator;
import org.gavaghan.geodesy.GlobalCoordinates;
import org.gavaghan.geodesy.GlobalPosition;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class Master extends Server implements IMaster {
    private int currentIteration;
    private int howManyWorkersToWait;

    private double latestError;
    private long loopCalculationStartTime;

    /* ArrayList of the POIs info loaded from json file */
    private Map<String, Poi> localPois;

    /* Available workers to distribute the work */
    private ArrayList<Worker> availableWorkers;

    /* Contains the response messages of workers */
    private ArrayList<CommunicationMessage> xMessages;
    private ArrayList<CommunicationMessage> yMessages;

    /* Execution Times of X,Y Matrices and Latest Distribution */
    private HashMap<String, Double> xExecutionTimes;
    private HashMap<String, Double> yExecutionTimes;
    private HashMap<String, Integer> latestWorkersXDistribution;
    private HashMap<String, Integer> latestWorkersYDistribution;

    /* Matrices */
    private INDArray RUpdated;
    private INDArray R, P, C, X, Y;

    /* Finals */
    private final static double L = 0.1;
    private final static double A = 40;

    private final static int MAX_ITERATIONS = 800;
    private final static double MIN_DIFFERENCE = 0.001;

    private final static String NEW_X_PATH = "data/newX.txt";
    private final static String NEW_Y_PATH = "data/newY.txt";

    /**
     * Constructors
     */
    private Master(String name, String ip, int port) {
        this.setName(name);
        this.setIp(ip);
        this.setPort(port);
    }

    public Master(String name, String ip, int port, int howManyWorkersToWait) {
        this(name, ip, port);
        this.howManyWorkersToWait = howManyWorkersToWait;
    }

    /**
     * Runnable Implementation
     */
    public synchronized void run() {
        /* Connection between Java Clients */
        ObjectOutputStream out = null;
        ObjectInputStream in = null;

        /* Connection Between Xamarin.Android Clients */
        DataInputStream dataIn = null;
        PrintWriter printOut = null;

        try {
            boolean isJavaConnection;
            boolean isAndroidConnection;
            CommunicationMessage message;

            /*
             * ************************************
             * ************ Warning!! *************
             * ******** Hacking The Kernel ********
             * ************************************
             * First try creating a Java Connection.
             * Remember, if it is not a Java Connection,
             * ObjectInputStream will cut the 4 first bytes because
             * it will think that this is the header of the message.
             * So The C# Client must send an empty header of 4 bytes!!!
             * By doing this way, the DataInputStream will read the message
             * PERFECTLY!!!
             */
            try {
                isJavaConnection = true;
                out = new ObjectOutputStream(getSocketConn().getOutputStream());
                in = new ObjectInputStream(getSocketConn().getInputStream());
            } catch (Exception e) {
                isJavaConnection = false;
                printOut = new PrintWriter(getSocketConn().getOutputStream());
                dataIn = new DataInputStream(getSocketConn().getInputStream());
            }

            if (isJavaConnection) {
                Object receivedObject = in.readObject();
                if (receivedObject instanceof String) {
                    message = new Gson()
                            .fromJson((String) receivedObject, CommunicationMessage.class);
                    isAndroidConnection = true;
                } else {
                    message = (CommunicationMessage) receivedObject;
                    isAndroidConnection = false;
                }
            } else {
                byte[] receivedBytes = new byte[dataIn.available()];
                for (int i = 0; i < receivedBytes.length; i++) {
                    receivedBytes[i] = dataIn.readByte();
                }

                String rec = new String(receivedBytes);
                message = new Gson().fromJson(rec, CommunicationMessage.class);
                isAndroidConnection = false;
            }

            switch (message.getType()) {
                case HELLO_WORLD: {
                    /* When a worker sends hello world add him to the list */
                    Worker worker = new Worker(message.getServerName(), message.getIp(), message.getPort());
                    worker.setInstanceCpuCores(message.getCpuCores());
                    worker.setInstanceRamSize(message.getRamGBSize());
                    availableWorkers.add(worker);

                    System.out.println(worker.toString());

                    /* If we reached our point, start the algorithm */
                    if (availableWorkers.size() >= howManyWorkersToWait) {
                        StartMatrixFactorization();
                    }

                    break;
                }
                case X_CALCULATED: {
                    xMessages.add(message);
                    xExecutionTimes.put(message.getServerName(), message.getExecutionTime());
                    if (xMessages.size() >= availableWorkers.size()) {
                        LinkedList<INDArray> XDist = new LinkedList<>();

                        /* Ascending sort of starting index */
                        xMessages.sort(Comparator.comparingInt(CommunicationMessage::getStartIndex));
                        xMessages.forEach((msg) -> XDist.add(msg.getXArray()));

                        /* Stack the matrices to one, sort MATTERS */
                        X = Nd4j.vstack(XDist);

                        DistributeXMatrixToWorkers();

                        //When finished, clear it for the next loop
                        xMessages.clear();
                    }

                    break;
                }
                case Y_CALCULATED: {
                    yMessages.add(message);
                    yExecutionTimes.put(message.getServerName(), message.getExecutionTime());
                    if (yMessages.size() >= availableWorkers.size()) {
                        LinkedList<INDArray> YDist = new LinkedList<>();

                        /* Ascending sort of starting index */
                        yMessages.sort(Comparator.comparingInt(CommunicationMessage::getStartIndex));
                        yMessages.forEach((msg) -> YDist.add(msg.getYArray()));

                        /* Stack the matrices to one, sort MATTERS */
                        Y = Nd4j.vstack(YDist);

                        /* Calculate the new error */
                        double error = CalculateError();
                        double difference = Math.abs(error - latestError);

                        System.out.println("***********************************************");
                        System.out.println("Loop No.: " + currentIteration);
                        System.out.println("Error: " + error);
                        System.out.println("Previous Error: " + latestError);
                        System.out.println("Difference: " + difference);
                        System.out.println("Loop Elapsed Time: " +
                                ParserUtils.GetTimeInSec(loopCalculationStartTime) + "sec");
                        System.out.println("***********************************************");

                        /* If we reached our limit of the difference or the iterations, end the algorithm */
                        if (difference < MIN_DIFFERENCE || currentIteration >= MAX_ITERATIONS) {
                            FinishMatrixFactorization();
                            return;
                        }

                        latestError = error;
                        currentIteration++;

                        DistributeYMatrixToWorkers();
                        loopCalculationStartTime = System.nanoTime();

                        //When finished, clear it for the next loop
                        yMessages.clear();
                    }
                    break;
                }
                case ASK_RECOMMENDATION: {
                    /* Accept only when the R is updated */
                    if (RUpdated == null) return;

                    List<Poi> userPois = CalculateBestLocalPOIsForUser(
                            message.getUserToAsk(),
                            message.getRadiusInKm(),
                            message.getUserLat(),
                            message.getUserLng());

                    CommunicationMessage result = new CommunicationMessage();
                    result.setType(MessageType.REPLY_RECOMMENDATION);
                    result.setPoisToReturn(userPois);

                    if (isJavaConnection) {
                        if (isAndroidConnection) {
                            out.writeObject(new Gson().toJson(result));
                            out.flush();
                        } else {
                            SendCommunicationMessage(result, message.getIp(), message.getPort());
                        }
                    } else {
                        printOut.print(new Gson().toJson(result));
                        printOut.flush();
                    }
                }
                default: {
                    break;
                }
            }
        } catch (ClassNotFoundException | IOException e) {
            e.printStackTrace();
        } finally {
            CloseConnections(in, out, dataIn, printOut);
        }
    }

    private void StartMatrixFactorization() {
        /* Calculate the number of total cores */
        int totalCores = availableWorkers
                .stream()
                .map(Worker::getInstanceCpuCores)
                .reduce(0, (a, b) -> a + b);

        System.out.println("Number Of Workers: " + availableWorkers.size() + " Workers");
        System.out.println("Total Cores: " + totalCores + " Cores");

        /* C and P matrices only need to be calculated once and passed once to the workers */
        C = (R.mul(A)).add(1);
        P = Transforms.greaterThanOrEqual(R, Nd4j.zeros(R.rows(), R.columns()));

        /* let's get the K << max{U,I}
           meaning a number much smaller than the biggest column or dimension */
        int BiggestDimension = R.columns() > R.rows() ?
                R.columns() : R.rows();

        int K = BiggestDimension / 10;

        X = MatrixHelpers.GenerateRandomMatrix(R, K, false);
        Y = MatrixHelpers.GenerateRandomMatrix(R, K, true);

        TransferMatricesToWorkers();
        DistributeYMatrixToWorkers();

        loopCalculationStartTime = System.nanoTime();
    }

    private void FinishMatrixFactorization() {
        System.out.println("**************************************");
        System.out.println("Writing to " + NEW_X_PATH + ", " + NEW_Y_PATH + "newY.txt");

        /* Write the X and Y Matrices */
        Nd4j.writeTxt(X, NEW_X_PATH);
        Nd4j.writeTxt(Y, NEW_Y_PATH);

        long startTime = System.nanoTime();

        /* Calculate the new R table */
        RUpdated = X.mmul(Y.transpose());

        System.out.println("New R Calculated in: " + ParserUtils.GetTimeInSec(startTime) + "sec");
        System.out.println("**************************************");
    }

    /**
     * IMaster Implementation
     */
    public void Initialize() {
        /* Starting values and initializations */
        currentIteration = 0;
        latestError = Double.MAX_VALUE - 1;

        availableWorkers = new ArrayList<>();

        xMessages = new ArrayList<>();
        yMessages = new ArrayList<>();

        xExecutionTimes = new HashMap<>();
        yExecutionTimes = new HashMap<>();
        latestWorkersXDistribution = new HashMap<>();
        latestWorkersYDistribution = new HashMap<>();

        /* Read the DataSet */
        R = ParserUtils.LoadDataSet("data/inputMatrix.csv");
        if (R == null) {
            System.out.println("Wrong DataSet! Please contact with the Developers!");
            return;
        }

        /* Init C and P */
        C = Nd4j.zeros(R.rows(), R.columns());
        P = Nd4j.zeros(R.rows(), R.columns());

        /* If the newX and the newY are existing, calculate the new R */
        Path newXFile = Paths.get(NEW_X_PATH);
        Path newYFile = Paths.get(NEW_Y_PATH);
        if (Files.exists(newXFile) && Files.exists(newYFile)) {
            X = Nd4j.readTxt(NEW_X_PATH);
            Y = Nd4j.readTxt(NEW_Y_PATH);
            System.out.println("**************************************");
            System.out.println("Loading to " + NEW_X_PATH + ", " + NEW_Y_PATH + "newY.txt");
            System.out.println("**************************************");

            // Create the updated R
            FinishMatrixFactorization();
        } else {
            System.out.println("No trained data found to load!. Waiting for master connections...");
        }

        localPois = ParserUtils.loadPoisFromJson();

        this.OpenServer();
    }

    public void TransferMatricesToWorkers() {
        CommunicationMessage msg = new CommunicationMessage();
        msg.setType(MessageType.TRANSFER_MATRICES);
        msg.setCArray(C);
        msg.setPArray(P);
        msg.setXArray(X);
        msg.setYArray(Y);

        SendBroadcastMessageToWorkers(msg);
    }

    public void DistributeXMatrixToWorkers() {
        HashMap<String, Integer[]> workerIndexes = SplitMatrix(Y, "Y");

        /* Send the messages parallel so the workers can be able to start at the same time */
        availableWorkers.parallelStream().forEach(worker -> {
            CommunicationMessage xMessage = new CommunicationMessage();
            xMessage.setType(MessageType.CALCULATE_Y);
            xMessage.setXArray(X);
            xMessage.setStartIndex(workerIndexes.get(worker.getName())[0]);
            xMessage.setEndIndex(workerIndexes.get(worker.getName())[1]);
            this.SendCommunicationMessage(xMessage, worker.getIp(), worker.getPort());
        });
    }

    public void DistributeYMatrixToWorkers() {
        HashMap<String, Integer[]> workerIndexes = SplitMatrix(X, "X");

        /* Send the messages parallel so the workers can be able to start at the same time */
        availableWorkers.parallelStream().forEach(worker -> {
            CommunicationMessage xMessage = new CommunicationMessage();
            xMessage.setType(MessageType.CALCULATE_X);
            xMessage.setYArray(Y);
            xMessage.setStartIndex(workerIndexes.get(worker.getName())[0]);
            xMessage.setEndIndex(workerIndexes.get(worker.getName())[1]);
            this.SendCommunicationMessage(xMessage, worker.getIp(), worker.getPort());
        });
    }

    public void SendBroadcastMessageToWorkers(CommunicationMessage message) {
        /* Send the messages parallel so the workers can be able to start at the same time */
        availableWorkers.parallelStream().forEach(worker ->
                this.SendCommunicationMessage(message, worker.getIp(), worker.getPort()));
    }

    public double CalculateError() {
        /* Compute the least square distance for every element */
        INDArray temp = X.mmul(Y.transpose());
        temp.subi(P);
        temp.muli(temp);
        temp.muli(C);

        /* compute the normalization */
        INDArray normX = Nd4j.sum(X.mul(X), 0);
        INDArray normY = Nd4j.sum(Y.mul(Y), 0);
        INDArray norma = normX.add(normY);
        norma.muli(L);

        /* Sum all the elements of the least squares */
        double sumPartOne = temp.sumNumber().doubleValue();

        /* Compute the normalization for every element */
        double sumPartTwo = norma.sumNumber().doubleValue();

        /* Return Error + norma */
        return sumPartOne + sumPartTwo;
    }

    public double CalculateScore(int x, int y) {
        return 0;
    }

    public List<Poi> CalculateBestLocalPOIsForUser(int user, int radius, double userLat, double userLng) {
        List<Poi> recommendedPOIs = new LinkedList<>();

        INDArray pois = RUpdated.getRow(user);

        double previousMax = Double.MAX_VALUE;
        for (int poi = 0; poi < pois.columns(); poi++) {
            int currentMaxIndex = getMax(pois, previousMax, user);
            previousMax = pois.getDouble(0, currentMaxIndex);

            /* Creates a new poi with that id */
            recommendedPOIs.add(localPois.get(String.valueOf(currentMaxIndex)));
        }

        /* Filter null values */
        recommendedPOIs = recommendedPOIs
                .parallelStream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        /* Calculate distances (User -> POI) for each poi */
        recommendedPOIs
                .parallelStream()
                .forEach(p -> p.setDistance((int) CalculateDistance(
                        userLat,
                        userLng,
                        p.getLatitude(),
                        p.getLongitude())));

        /* Got only pois inside the radius */
        recommendedPOIs = recommendedPOIs
                .parallelStream()
                .filter(p -> p.getDistance()/1000 <= radius)
                .collect(Collectors.toList());

        /* Sort ascending base on poi's distance from user */
        recommendedPOIs.sort(Comparator.comparingInt(Poi::getDistance));

        return recommendedPOIs;
    }

    public double CalculateDistance(double userLat, double userLon, double poiLat, double poiLon){
        GlobalCoordinates poiPos = new GlobalCoordinates(poiLat, poiLon);
        GlobalCoordinates userPos = new GlobalCoordinates(userLat, userLon);

        return new GeodeticCalculator()
                .calculateGeodeticCurve(Ellipsoid.WGS84, userPos, poiPos).getEllipsoidalDistance();
    }

    private static double CalculateDistanceManually(double userLat, double userLon, double poiLat, double poiLon) {
        double theta = userLon - poiLon;

        double dist = Math.sin(
                deg2rad(userLat)) *
                Math.sin(deg2rad(poiLat)) + Math.cos(deg2rad(userLat)) *
                Math.cos(deg2rad(poiLat)) *
                Math.cos(deg2rad(theta));

        dist = Math.acos(dist);
        dist = rad2deg(dist);
        dist = dist * 60 * 1.1515;
        dist = dist * 1.609344;

        return dist;
    }

    private static double deg2rad(double deg) {
        return (deg * Math.PI / 180.0);
    }

    private static double rad2deg(double rad) {
        return (rad * 180 / Math.PI);
    }
    
    /**
     * Helper Methods
     */
    public HashMap<String, Integer[]> SplitMatrix(INDArray matrix, String matrixName) {
        int totalCores = availableWorkers
                .stream()
                .map(Worker::getInstanceCpuCores)
                .reduce(0, (a, b) -> a + b);

        int currentIndex = -1;

        int rowsPerCore = 0;
        LinkedHashMap<String, Double> sortedMap =
                new LinkedHashMap<>();

        /*
          Execution times HashMap. Keys: Worker's name,
          Values: latest execution time of the worker's duty
         */
        HashMap<String, Double> matrixExecutionTimes =
                new HashMap<>(matrixName.equals("X") ?
                        xExecutionTimes :
                        yExecutionTimes);

        /*
          Workers Distribution HashMap. Keys: Worker's name,
          Values: latest number of matrix's rows given to create
         */
        HashMap<String, Integer> latestWorkersDistribution =
                matrixName.equals("X") ?
                        latestWorkersXDistribution :
                        latestWorkersYDistribution;

        /*
         * During the first iteration we distribute only depending on the
         * cores of each worker, so we divide the rows to the cores evenly
         */
        if (currentIteration == 0) {
            rowsPerCore = matrix.rows() / totalCores;
        }
        /*
         * Otherwise we distribute depending the previous execution times
         */
        else {
            /* Create a sorted version HashMap of the matrixExecutionTimes HashMap */
            ArrayList<String> mapKeys = new ArrayList<>(matrixExecutionTimes.keySet());
            ArrayList<Double> mapValues = new ArrayList<>(matrixExecutionTimes.values());
            Collections.sort(mapValues);
            Collections.sort(mapKeys);

            for (Double val : mapValues) {
                Iterator<String> keyIt = mapKeys.iterator();

                while (keyIt.hasNext()) {
                    String key = keyIt.next();

                    if (matrixExecutionTimes.get(key).equals(val)) {
                        keyIt.remove();
                        sortedMap.put(key, val);
                        break;
                    }
                }
            }
        }

        HashMap<String, Integer[]> workerIndexes = new HashMap<>();

        /*
           Calculate min execution time in order to decide later
           if we need to redistribute
         */
        double totalExTime = matrixExecutionTimes.values()
                .stream()
                .reduce(0.0, (a, b) -> a + b);

        double meanExTime = totalExTime / availableWorkers.size();

        /* Find the last value(ex time), meaning the slowest worker's time */
        Iterator<Double> valueIt = sortedMap.values().iterator();
        Double lastValue = 0.0;
        while (valueIt.hasNext()) {
            lastValue = valueIt.next();
        }

        System.out.println("***********************************************");

        /*
           No need to distribute if we are in the first loop
           or the difference between the slowest time and the
           min is lower than 1 sec
         */
        boolean NoNeedRedistribution = currentIteration == 0 || (lastValue - meanExTime) <= 0;

        /*
         * If we do need to distribute, we remove 10% of the rows
         * given to the slowest worker, and share it among the rest workers
         */
        if (!NoNeedRedistribution) {
            Iterator<String> keyIt = sortedMap.keySet().iterator();
            String lastKey = "";
            while (keyIt.hasNext()) {
                lastKey = keyIt.next();
            }

            int slowestWorkerIndices = latestWorkersDistribution.get(lastKey);
            int percentMoved = slowestWorkerIndices / 10;
            latestWorkersDistribution.put(lastKey, slowestWorkerIndices - percentMoved);
            int rowsToBeAddedToRest = percentMoved / (availableWorkers.size() - 1);

            for (String currentKey : sortedMap.keySet()) {
                if (currentKey.equals(lastKey)) break;
                int currentIndices = latestWorkersDistribution.get(currentKey);
                latestWorkersDistribution.put(currentKey, currentIndices + rowsToBeAddedToRest);
                percentMoved -= rowsToBeAddedToRest;
            }

            /* In case there are rows left, we add them to the first worker */
            if (percentMoved > 0) {
                Iterator<String> keyIt3 = sortedMap.keySet().iterator();
                if (keyIt3.hasNext()) {
                    String currentKey = keyIt3.next();
                    int currentIndices = latestWorkersDistribution.get(currentKey);
                    latestWorkersDistribution.put(currentKey, currentIndices + percentMoved);
                }
            }
        }

        /* For each worker, we set the number of rows
           depending on the need or not for distribution */
        for (int i = 0; i < availableWorkers.size(); i++) {
            Worker worker = availableWorkers.get(i);

            /*
               If we don't need to redistribute, we distribute the
               rows equally to the number of the cores it has.
               Otherwise, we do the redistribution
             */
            int workerRows = NoNeedRedistribution ?
                    (rowsPerCore * worker.getInstanceCpuCores()) :
                    latestWorkersDistribution.get(worker.getName());

            Integer[] indexes = new Integer[2];
            indexes[0] = currentIndex + 1;

            if (i == availableWorkers.size() - 1) {
                if (currentIndex != matrix.rows()) {
                    indexes[1] = matrix.rows() - 1;
                    System.out.println("Distributing " + matrixName +
                            " to " + worker.getName() +
                            " from " + indexes[0] +
                            " to " + indexes[1] +
                            ". Total: " + (indexes[1] - indexes[0] + 1));
                }
            } else {
                currentIndex += workerRows;
                indexes[1] = currentIndex;
                System.out.println("Distributing " + matrixName +
                        " to " + worker.getName() +
                        " from " + indexes[0] +
                        " to " + indexes[1] +
                        ". Total: " + (indexes[1] - indexes[0] + 1));
            }
            latestWorkersDistribution.put(worker.getName(), indexes[1] - indexes[0] + 1);
            workerIndexes.put(worker.getName(), indexes);
        }

        System.out.println("***********************************************");

        return workerIndexes;
    }

    private int getMax(INDArray pois, double previousMax, int user) {
        double max = -1;
        int maxIndex = -1;
        for (int i = 0; i < pois.columns(); i++) {
            double element = pois.getDouble(0, i);
            if (previousMax > element &&
                    element > max &&
                    P.getDouble(user, i) != 1) {
                max = element;
                maxIndex = i;
            }
        }
        return maxIndex;
    }

    /**
     * Getters and Setters
     */
    public int getHowManyWorkersToWait() {
        return howManyWorkersToWait;
    }

    public void setHowManyWorkersToWait(int howManyWorkersToWait) {
        this.howManyWorkersToWait = howManyWorkersToWait;
    }

}
