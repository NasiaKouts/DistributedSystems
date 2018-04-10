package gr.aueb.dist.partOne.Utils;

import gr.aueb.dist.partOne.Models.Master;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.StringTokenizer;

public class ParserUtils {
    public static Master GetServersFromText(String file){
        Master master = new Master();

        BufferedReader bufferedReader = null;
        FileReader fileReader = null;
        try {
            fileReader = new FileReader(file);
            bufferedReader = new BufferedReader(fileReader);

            String sCurrentLine;
            while ((sCurrentLine = bufferedReader.readLine()) != null) {
                StringTokenizer st = new StringTokenizer(sCurrentLine);
                int i = 1;
                while (st.hasMoreTokens()){
                    String token = st.nextToken();

                    switch (i){
                        case 1: master.setIp(token); break;
                        case 2: master.setPort(Integer.parseInt(token)); break;
                        case 3: master.setId(token); break;
                        case 4: master.setHowManyWorkersToWait(Integer.parseInt(token)); break;
                    }
                    i++;
                }
            }

            return master;
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        finally {
            CloseReaders(bufferedReader, fileReader);
        }

        return null;
    }

    public static INDArray loadDataSet(String dataSet){
        // the index start from zero to 764. So the dimension will be 765
        INDArray matrix = Nd4j.zeros(765, 1964);

        BufferedReader bufferedReader = null;
        FileReader fileReader = null;
        try {
            fileReader = new FileReader(dataSet);
            bufferedReader = new BufferedReader(fileReader);

            String sCurrentLine;
            while ((sCurrentLine = bufferedReader.readLine()) != null) {
              // separate the commas
                sCurrentLine = sCurrentLine.replaceAll("\\s","");
                StringTokenizer tokenizer = new StringTokenizer(sCurrentLine,",");
                int row = Integer.parseInt(tokenizer.nextToken());
                int column = Integer.parseInt(tokenizer.nextToken());
                int value = Integer.parseInt(tokenizer.nextToken());
                matrix.put(row,column,value);
            }

        }
        catch (IOException e) {
            e.printStackTrace();
        }
        finally {
            CloseReaders(bufferedReader, fileReader);
        }

        return matrix;
    }

    private static void CloseReaders(BufferedReader bufferedReader, FileReader fileReader) {
        try {
            if (bufferedReader != null){
                bufferedReader.close();
            }
            if (fileReader != null){
                fileReader.close();
            }
        }
        catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public static void DistributeNumberOfCores(int totalWorkers, int coresPerWorker, int totalCores, int totalRows){
        int currentIndex = -1;
        int rowsPerCore = totalRows / totalCores;
        System.out.println(rowsPerCore + " Rows per Core");
        for(int i = 0; i < totalWorkers; i++){
            int workerRows = rowsPerCore * coresPerWorker;

            if(i == totalWorkers - 1){
                if(currentIndex != totalRows){
                    System.out.println("Last Loop From User: " + (currentIndex + 1) + " to user: " + (totalRows-1));
                }
            }else{
                int prevIndex = currentIndex + 1;
                currentIndex += workerRows;
                System.out.println("From User: " + prevIndex + " to user: " + currentIndex);
            }
        }
    }

    public static void PrintShape(INDArray array){
        System.out.println("Rows: "+array.rows()+ " Columns: "+array.columns());
    }

    public static long GetTimeInMs(long start){
        long end = System.nanoTime();
        return (end-start)/1000000;
    }
}
