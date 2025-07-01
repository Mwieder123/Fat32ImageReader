import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.*;

public class fat_32_reader {
    //Boot Sector Information
    private final int BPB_BytesPerSec;
    private final int BPB_SecPerClus;
    private final int BPB_RsvdSecCnt;
    private final int BPB_NumFATS;
    private final int BPB_FATSz32;
    private final int fatOffset;
    private final int dataOffset;
    private final int maxCluster;
    private final int rootCluster;

    //Accessor and Fat Cluster Lists
    private final RandomAccessFile raf;
    private final HashMap<Integer, List<Integer>> clusterLists1;
    private final HashMap<Integer, List<Integer>> clusterLists2;

    //Current Working Directory Information
    private int currentCluster;
    private HashMap<String,FAT_Entry> currentChildren;
    private String currentWorkingDirectory;


    public fat_32_reader(String fileImage) throws IOException {
        this.raf = new RandomAccessFile(fileImage, "r");

        //Get all the boot sector info
        this.BPB_BytesPerSec = getBytes(12, 2);
        this.BPB_SecPerClus = getBytes(13, 1);
        this.BPB_RsvdSecCnt = getBytes(15, 2);
        this.BPB_NumFATS = getBytes(16, 1);
        this.BPB_FATSz32 = getBytes(39, 4);
        this.rootCluster = getBytes(47,4);

        //Get structure info and initialize data structures
        this.fatOffset = this.BPB_BytesPerSec * this.BPB_RsvdSecCnt;
        this.dataOffset = this.BPB_BytesPerSec * this.BPB_NumFATS * this.BPB_FATSz32 + this.fatOffset;
        this.clusterLists1 = new HashMap<>();
        this.clusterLists2 = new HashMap<>();
        this.currentChildren = new HashMap<>();

        int fsInfoSector = getBytes(49,2);
        int nextFree = fsInfoSector * this.BPB_BytesPerSec + 493;
        this.maxCluster = getBytes(nextFree, 2);

        //Move into Root directory
        readFAT1();
        this.currentCluster = this.rootCluster;
        this.currentWorkingDirectory = "/";
        this.getChildren(this.rootCluster);
        //Deal with Fat2 and bad clusters
      //  readFAT2();
    }

    public void info(){
        System.out.println("BPB_BytesPerSec is 0x"+ Integer.toHexString(BPB_BytesPerSec) + ", " + BPB_BytesPerSec);
        System.out.println("BPB_SecPerClus is 0x"+ Integer.toHexString(BPB_SecPerClus) + ", " + BPB_SecPerClus);
        System.out.println("BPB_RsvdSecCnt is 0x"+ Integer.toHexString(BPB_RsvdSecCnt) + ", " + BPB_RsvdSecCnt);
        System.out.println("BPB_NumFATS is 0x"+ Integer.toHexString(BPB_NumFATS) + ", " + BPB_NumFATS);
        System.out.println("BPB_FATSz32 is 0x" + Integer.toHexString(BPB_FATSz32)+ ", " + BPB_FATSz32);
    }

    public void ls(){
        List<String> children = new ArrayList<>(this.currentChildren.keySet());
        Collections.sort(children);

        for(String name : children){
            System.out.print(name + " ");
        }
        System.out.println();
    }
    public void stat(String fileName){
        FAT_Entry entry = this.currentChildren.get(fileName);

        if(entry == null){
            System.out.println("Error: file/directory does not exist");
            return;
        }
        System.out.println("Size is " + entry.size);
        StringBuilder attributes = new StringBuilder("Attributes");
        boolean has = false;

        //Find attributes
        char[] attributesString = Integer.toBinaryString(entry.attributes).toCharArray();
        int thisSize = attributesString.length;

        if(thisSize >= 6 && attributesString[0] == '1'){
            attributes.append(" ATTR_ARCHIVE");
            has = true;
        }
        if(thisSize >= 5 && attributesString[thisSize-5] == '1'){
            attributes.append(" ATTR_DIRECTORY");
            has = true;
        }
        if(thisSize >= 4 && attributesString[thisSize-4] == '1'){
            attributes.append(" ATTR_VOLUME_ID");
            has = true;
        }
        if(thisSize >= 3 && attributesString[thisSize-3] == '1'){
            attributes.append(" ATTR_SYSTEM");
            has = true;
        }
        if(thisSize >= 2 && attributesString[thisSize-2] == '1'){
            attributes.append(" ATTR_HIDDEN");
            has = true;
        }
        if(thisSize >= 1 && attributesString[thisSize-1] == '1'){
            attributes.append(" ATTR_READ_ONLY");
            has = true;
        }
        if(!has){
            attributes.append(" NONE");
        }

        System.out.println(attributes.toString());
        System.out.println("Next cluster number is 0x" + String.format("%08x", entry.firstClusterNumber));
    }

    public void size(String fileName) throws IOException {
        FAT_Entry entry = this.currentChildren.get(fileName);

        if(entry == null){
            System.out.println("Error: " + fileName + " is not a file");
            return;
        }
        if(entry.isDirectory){
            System.out.println("Error: " + fileName + " is not a file");
        }
        else
            System.out.println("Size of " + fileName + " is " + entry.size + " bytes");
    }

    public boolean cd(String directoryName) throws IOException {
        FAT_Entry entry = this.currentChildren.get(directoryName);

        if(entry == null){
            System.out.println("Error: " + directoryName + " is not a directory");
            return true;
        }
        if(!entry.isDirectory){
            System.out.println("Error: " + directoryName + " is not a directory");
            return true;
        }
        else{
            //Deal with root cluster
            if(this.currentCluster == this.rootCluster){
                this.currentCluster = entry.firstClusterNumber;

                if(!directoryName.equals(".") && !directoryName.equals("..")){
                    currentWorkingDirectory+=("/" + directoryName.toUpperCase());
                }

                this.getChildren(this.currentCluster);
            }
            else{
                //Deal with other clusters
                this.currentCluster = entry.firstClusterNumber;

                if(directoryName.equals("..")){
                    if(!currentWorkingDirectory.equals("/")){
                        int index = currentWorkingDirectory.lastIndexOf("/");
                        currentWorkingDirectory = currentWorkingDirectory.substring(0, index);
                    }
                }
                else if(!directoryName.equals(".")){
                    currentWorkingDirectory+=("/" + directoryName.toUpperCase());
                }

                this.getChildren(this.currentCluster);
            }

            return false;
        }
    }
    public void read(String fileName, int offset, int numberOfBytes) throws IOException {
        FAT_Entry entry = this.currentChildren.get(fileName);

        if(entry.firstClusterNumber == 0){
            System.out.println("Error: attempt to read data outside of file bounds");
            return;
        }

        if(entry == null){
            System.out.println("Error: " + fileName + " is not a file");
            return;
        }
        if(entry.isDirectory){
            System.out.println("Error: " + fileName + " is not a file");
            return;
        }
        if(numberOfBytes <= 0){
            System.out.println("Error: NUM_BYTES must be greater than zero");
            return;
        }
        if(offset < 0){
            System.out.println("Error: OFFSET must be a positive value");
            return;
        }
        //Assumption made that files stores contiguosly
        List<Integer> thisClusterList = this.clusterLists1.get(entry.firstClusterNumber);
        int lastCluster = thisClusterList.get(thisClusterList.size() - 1);

        int minOffset = getOffsetFromCluster(entry.firstClusterNumber);
        int maxOffset = getOffsetFromCluster(lastCluster);

        int maxOffsetOfBytes = ((maxOffset - minOffset) +1) * this.BPB_BytesPerSec * this.BPB_SecPerClus;

        if(offset+numberOfBytes >= maxOffsetOfBytes){
            System.out.println("Error: attempt to read data outside of file bounds");
            return;
        }

        String dataRead = getBytesForRead(minOffset+offset, numberOfBytes);
        System.out.println(dataRead);
    }
    private void readFAT1() throws IOException {
        HashSet<Integer> clustersVisited = new HashSet<>();
        List<Integer> clusterList = new ArrayList<>();
        int currentCluster = 2;

        for(int i = 2; i < this.maxCluster; i++){
            currentCluster = i;
            int currentOffset = 3 + this.fatOffset + 4*i;
            int nextCluster = getBytes(currentOffset, 4);

            //If next cluster exists and has not been visited, then visit it
            if(nextCluster != 0 && clustersVisited.add(currentCluster)){
                clusterList.add(currentCluster);

                //Add more clusters if the cluster chain hasn't ended
                while(nextCluster <  268435448 || nextCluster > 268435455){
                    clusterList.add(nextCluster);
                    clustersVisited.add(nextCluster);
                    currentCluster = nextCluster;
                    nextCluster = getBytes((3 + this.fatOffset + 4 * currentCluster), 4);
                }
                this.clusterLists1.put(i, clusterList);
                clusterList = new ArrayList<>();
            }
        }
    }
    private void readFAT2() throws IOException {
        int currentOffset = 8 + this.fatOffset + this.BPB_FATSz32 * this.BPB_BytesPerSec;
        int firstCluster = 2;
        int currentCluster = 2;

        List<Integer> clusterList = new ArrayList<>();
        HashSet<Integer> firstSectors = new HashSet<>();
        clusterList.add(currentCluster);

        while(currentOffset < this.dataOffset){
            int nextCluster = getBytes(currentOffset, 4);
            clusterList.add(nextCluster);

            if(nextCluster >= 268435448 && nextCluster <= 268435455){
                this.clusterLists2.put(firstCluster, clusterList);
                clusterList = new ArrayList<>();

            }
            currentOffset += 4;
        }
    }
    private String getBytesAsString(int offset, int size)throws IOException {
        StringBuilder byteString = new StringBuilder();

        //Go digit by digit appending the hex characters
        for(int i = 0; i < size; i++){
            this.raf.seek(offset);
            byteString.append(String.format("%02x", this.raf.read()));
            offset--;
        }

        char[] charArray = byteString.toString().toCharArray();
        StringBuilder convertedString = new StringBuilder();

        //Go digit by digit converting to numerical/decimal characters
        for(int i = charArray.length-1; i >= 0; i-=2){
            String st = ""+ charArray[i-1]+charArray[i];
            char ch = (char)Integer.parseInt(st, 16);
            convertedString.append(ch);
        }
        return convertedString.toString();
    }

    private String getBytesForRead(int offset, int size) throws IOException {
        StringBuilder byteString = new StringBuilder();

        //Go digit by digit appending the hex characters
        for(int i = 0; i < size; i++){
            this.raf.seek(offset);
            byteString.append(String.format("%02x", this.raf.read()));
            offset++;
        }

        char[] charArray = byteString.toString().toCharArray();
        StringBuilder convertedString = new StringBuilder();

        //Go digit by digit converting to numerical/decimal characters
        for(int i = 0; i < charArray.length; i+=2){
            String st = ""+ charArray[i]+charArray[i+1];
            int chAsInt = Integer.parseInt(st, 16);

            //Check if in allowed value
            if(chAsInt ==10 || (chAsInt >=32 && chAsInt <= 126)){
                convertedString.append((char)chAsInt);
            }
            else{
             convertedString.append("0x").append(st);
            }
        }
        return convertedString.toString();
    }

    private int getBytes(int offset, int size) throws IOException {
        StringBuilder byteString = new StringBuilder();

        //Go digit by digit adding hex digits
        for(int i = 0; i < size; i++){
            this.raf.seek(offset);
            byteString.append(String.format("%02x", this.raf.read()));
            offset--;
        }

        //return decimal value
        return Integer.parseInt(String.valueOf(byteString), 16);
    }

    private int getOffsetFromCluster(int cluster){
        if(cluster == 2){
            return  this.dataOffset;
        }
        else return this.dataOffset + (cluster-2) * this.BPB_SecPerClus * this.BPB_BytesPerSec;
    }

    private void getChildren(int cluster) throws IOException {
        HashMap<String,FAT_Entry> children = new HashMap<>();

        int offset = getOffsetFromCluster(cluster);
        int maxOffset = offset + this.BPB_BytesPerSec * this.BPB_SecPerClus;

        if(cluster == this.rootCluster){
            offset += 32;

            //Add current
            int attributes = getBytes(this.dataOffset+11, 1);
            int size = getBytes(this.dataOffset+31, 4);

            FAT_Entry entry = new FAT_Entry(size,attributes,this.rootCluster,true,".");
            children.put(".",entry);

            //Add Parent
            FAT_Entry entry2 = new FAT_Entry(size,attributes,this.rootCluster,true,"..");
            children.put("..",entry);
        }
        else{
            //Parent directory
            offset+=32;
            getChildInfo(children, offset);
            //CWD
            getChildInfo(children, offset-32);

            offset+=32;
        }

        while(offset+32 < maxOffset){
            //Skip long name
            offset+=32;

            getChildInfo(children, offset);

            //Go to next entry
            offset+=32;
        }

        //Check next clusters in the chain
        for(int clus : this.clusterLists1.get(cluster)){
            if(clus == cluster){
                continue;
            }
            offset = getOffsetFromCluster(clus);
            maxOffset = offset + this.BPB_BytesPerSec * this.BPB_SecPerClus;

            while(offset+32 < maxOffset){

                //Skip long name
                offset+=32;

                getChildInfo(children, offset);

                //Go to next entry
                offset+=32;
            }
        }

        this.currentChildren = new HashMap<>(children);
    }
    private void getChildInfo(HashMap<String,FAT_Entry> children, int offset) throws IOException {
        String shortName = getBytesAsString(offset+10, 11);
        if(shortName.trim().isBlank()){
            return;
        }
        int attributes = getBytes(offset+11, 1);

        char[] attr_array = Integer.toBinaryString(attributes).toCharArray();

        boolean isDirectory = false;

        if(attr_array.length >= 5 && attr_array[attr_array.length-5] == '1'){
            isDirectory = true;
        }


        int firstClusterHigh = getBytes(offset+21, 2);
        int firstClusterLow = getBytes(offset+27, 2);
        int size = getBytes(offset+31, 4);

        String hexHigh = Integer.toHexString(firstClusterHigh);
        String hexLow = Integer.toHexString(firstClusterLow);

        String firstClusterString = hexHigh + hexLow;
        int firstCLusterInt = Integer.parseInt(firstClusterString, 16);

        if(attributes == 8 ){
            //This is root
            firstCLusterInt = this.rootCluster;
        }
        int index = currentWorkingDirectory.lastIndexOf("/");
        String tryRoot = currentWorkingDirectory.substring(0, index);


        String fixedString = shortName;
        if(!isDirectory){
            StringBuilder fixString = new StringBuilder(shortName);
            fixString.insert(fixString.length()-3,".");
            fixedString = fixString.toString();
        }
        fixedString = fixedString.replaceAll(" ", "");

        if(tryRoot.equals("/") && fixedString.equals("..")){
            firstCLusterInt = this.rootCluster;
        }

        FAT_Entry entry = new FAT_Entry(size,attributes,firstCLusterInt,isDirectory,fixedString);

        children.put(fixedString,entry);
    }
    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner(System.in);
        String fileImage = args[0];
        fat_32_reader reader = new fat_32_reader(fileImage);

        String CWD = "/] ";
        while(true){
            System.out.print(CWD);

            String command = scanner.nextLine();

            String[] arguments = command.split(" ");
            System.out.println(command);

            if(arguments[0].equals("stop")){
                break;
            }
            else if(arguments[0].equals("info")){
                reader.info();
            }
            else if(arguments[0].equals("ls")){
                reader.ls();
            }
            else if(arguments[0].equals("stat")){
                reader.stat(arguments[1].toUpperCase());
            }
            else if(arguments[0].equals("size")){
                reader.size(arguments[1].toUpperCase());
            }
            else if(arguments[0].equals("cd")){
                boolean error = reader.cd(arguments[1].toUpperCase());

                if(error){
                    continue;
                }

                if(arguments[1].equals(".")){
                    continue;
                }
                else if(arguments[1].equals("..")){
                    if(!CWD.equals("/] ")){
                        int index = CWD.lastIndexOf("/");
                        CWD = CWD.substring(0, index);
                        CWD+= "] ";
                    }
                }
                else{
                    CWD = CWD.substring(0, CWD.length()-2);
                    if(!CWD.equals("/"))
                        CWD+="/";
                    CWD+=arguments[1].toUpperCase();
                    CWD+= "] ";
                }

                if(CWD.equals("] ")){
                    CWD = "/] ";
                }
            }
            else if(arguments[0].equals("read")){
                reader.read(arguments[1].toUpperCase(), Integer.parseInt(arguments[2]), Integer.parseInt(arguments[3]));
            }
            else{
                System.out.println("Unknown command: " + command);
            }

        }
    }
    public class FAT_Entry {
        private final int size;
        private final int attributes;
        private final int firstClusterNumber;
        private final boolean isDirectory;
        private final String name;

        public FAT_Entry(int size, int attributes, int nextClusterNumber, boolean isDirectory, String name) {
            this.size = size;
            this.attributes = attributes;
            this.firstClusterNumber = nextClusterNumber;
            this.isDirectory = isDirectory;
            this.name = name;
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            return this.name.equals(((FAT_Entry) o).name);
        }
    }
}

