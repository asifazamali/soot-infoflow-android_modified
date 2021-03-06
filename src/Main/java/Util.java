package Main.java;
import Main.java.MakeRWLabel;
import beaver.*;
import beaver.Scanner;
import callgraph.InformationFlowAnalysis;
import callgraph.StatementHanding;
import fj.Hash;
import fj.data.Array;
import ifc.LabelManager;
import ifc.RWLabel;
import soot.*;
import soot.jimple.InvokeExpr;
import soot.jimple.infoflow.android.manifest.ProcessManifest;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.graph.UnitGraph;
import soot.toolkits.scalar.ArraySparseSet;
import soot.util.Chain;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Logger;


public class Util {
    public static final Logger LOGGER = Logger.getLogger("APK_CG");
    public static final String UNKNOWN = "<unknown>";
    public static ArrayList<String> classes= new ArrayList<>();
    public static ArrayList<String> methods= new ArrayList<>();
    public static ArrayList<String> apis= new ArrayList<>();
    public static ArrayList<String> apiMethod = new ArrayList<>();
    public static HashMap<String,ArrayList> sensitive_api_method = new HashMap<>();
    public static HashMap<String,ArrayList> global_output_api_method = new HashMap<>();
    public static HashSet<String> sensitive_class = new HashSet<>();
    public static String appPackageName="";
    public static HashMap<SootClass,ArrayList> classMethodDict = new HashMap<>();
    public static Dictionary dict_class_method = new Hashtable(); // SootClass-->{SootMethod}
    public static Dictionary dict_methodName_method = new Hashtable();//MethodName-->{SootMethod}
    public static LabelManager labelManager;
    public static MakeRWLabel makeRWLabel;
    public static PrintStream ps;
    public static Dictionary subLabel;
    public static HashSet<String> privateFields = new HashSet<>();
    public static HashMap<String,HashSet> fieldsLocals = new HashMap();
    public static HashMap<String,String> staticFields = new HashMap<>();
    public static HashSet<String> privateStaticFields =  new HashSet<>();
    public static HashMap<String,String> varIntent = new HashMap<>();
    public static HashMap<String,String> classVar = new HashMap<>();

    
    public static void readLogFile(){
        try{
            BufferedReader bufferedReader = new BufferedReader(new FileReader(Config.filesToProcess));
            String line = bufferedReader.readLine();
            HashSet<String> hashSet = new HashSet<>();
            /*each line is in the form of 
            V/DroidBox( <some_number>): <name_of_class> -> <name_of_member_function> -> <api_class_name> -> <api_name>  
            storing hashSet with keys as (<name_of_class>+" "+<name_of_member_function>) and values as(<api_class_name+" "+<api_name>)
            
             */
            while(line!=null)
            {
                String[] parts = line.split(":");
                System.out.println(line);
                System.out.println("parts:" + parts[1]);
                String[] partsSplit = parts[1].split("->");
                String key = "partSplit:" + partsSplit[0] + " " + partsSplit[1];
                System.out.println("partSplit:" + partsSplit[0] + " " + partsSplit[1] + " " + partsSplit[2] + partsSplit[3]);
                if (!hashSet.contains(key)){
                    classes.add(partsSplit[0]);
                    System.out.println(partsSplit[0]);
                    methods.add(trimMethods(partsSplit[1]));
                    apis.add(partsSplit[2]);
                    apiMethod.add(trimApiMethod(partsSplit[3]));
                    hashSet.add(key);
                }
                line = bufferedReader.readLine();
            }
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }
    
    
    public static void readSensitiveApiFile(){
        try{
            BufferedReader bufferedReader = new BufferedReader(new FileReader(Config.sensitiveApiFile));
            System.out.println("sensitive_apis.txt");
            String line = bufferedReader.readLine();
            /*
            Each line can be :
            Landroid/location/Location; ---> store as a sensitive class (used for handling static variables )
             or 
             Landroid/location/Location;->getLongitude --> stored as a sensitive function
             */
            while(line!=null){
                String[] parts = line.split("->");
                System.out.println(line);
                if(parts.length >1)
                {
                    if (sensitive_api_method.get(parts[0]) != null)
                        sensitive_api_method.get(parts[0]).add(convertClass(parts[0]));
                    else
                    {
                        ArrayList<String> stringArrayList = new ArrayList();
                        stringArrayList.add(parts[1]);
                        sensitive_api_method.put(convertClass(parts[0]), stringArrayList);
                    }
                
                }
                else{
                    sensitive_class.add(convertClass(line));
                }
                line = bufferedReader.readLine();
            }
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }
    public static void readGlobalOutputFile(){
        try{
            BufferedReader bufferedReader = new BufferedReader(new FileReader(Config.outputGlobalFile));
            String line = bufferedReader.readLine();
            
            /*
            same as readSensitiveApiFile
             */
            while(line!=null){
                System.out.println(line);
                String[] parts = line.split("->");
                if(global_output_api_method.get(parts[0]) != null)
                    global_output_api_method.get(convertClass(parts[0])).add(parts[1]);
                else{
                    ArrayList<String> stringArrayList = new ArrayList();
                    stringArrayList.add(parts[1]);
                    global_output_api_method.put(convertClass(parts[0]),stringArrayList);
                }
                line = bufferedReader.readLine();
            }
        }
        catch(Exception e){
            e.printStackTrace();
        }
    }
    
    
    /* whole processing starts here */
    
    public static void processing(String apkPath){
        try
        {
            ProcessManifest processManifest = new ProcessManifest(apkPath);
            appPackageName = processManifest.getPackageName();
            System.out.println(appPackageName);
        }
        catch(Exception e){
            e.printStackTrace();
        }
        
        /*setting logger output stream */
        
        ps = Config.getResultPs();
        System.out.println("Reading log file");
        readLogFile();
        System.out.println("Reading sensitiveApi (source) file");
        readSensitiveApiFile();
        System.out.println("Reading global output (sink) file");
        readGlobalOutputFile();
        Chain<SootClass> classes = Scene.v().getClasses();
        Iterator<SootClass> sootClassIterator = classes.iterator();
        HashSet<SootClass> sootClassHashSet = new HashSet<SootClass>();
        System.out.println("package name"+ appPackageName);
        /*
        iterate over classes
         */
        while(sootClassIterator.hasNext())
        {
            SootClass sootClass = sootClassIterator.next();
            String className = sootClass.getName();
            if(className.contains(appPackageName))
            {
                sootClassHashSet.add(sootClass);
                List<SootMethod> methodList = sootClass.getMethods();
                Iterator<SootMethod> methodIterator = methodList.iterator();
                System.out.println("className "+className);
                while(methodIterator.hasNext()){
                    SootMethod sootMethod = methodIterator.next();
                      System.out.println(sootMethod.getName());
                      /*
                      used for mapping - methodName -> sootMethod
                       */
                    dict_methodName_method.put(sootMethod.getName(),sootMethod);
                }
                /*
                dict - sootClass -> listof methods in class
                 */
                dict_class_method.put(sootClass,methodList);
            }    
        }
//        getJimpleFile();   //<-----------gives only jimple file

       flowControl();        //<----------- Labeling

    }
    
    
    /*
    print jimple file of apk using ps logger outputstream in cg.txt file
     */
    public static void getJimpleFile()
    {
        for (int i = 0; i < classes.size(); i++)
        {
            ps.println("**************************************************");
            ps.println(classes.get(i) + "." + methods.get(i) + "." + apis.get(i));
            ps.println("***************************************************");

            SootMethod sootMethod = (SootMethod) dict_methodName_method.get(methods.get(i));

            if(sootMethod != null)
            {
                Body b = sootMethod.retrieveActiveBody();
                UnitGraph unitGraph = new ExceptionalUnitGraph(b);
                Iterator itr = unitGraph.iterator();
                while (itr.hasNext())
                {
                    Unit u = (Unit) itr.next();
                    ps.println(u.toString());
                    //                ps.print("Out : ");
                    //                ps.println(OUT.toString() + "\n");
                }
            }
        }
    }
    /*
    prints jimple with label in cg.txt and sensitive apis and reporting of misuses, gives logs info on outputstream
     */
    
    
    public static void flowControl(){
        ps.println("flowAnalysis");
        HashSet refLocals;
        createSubjectLabel();
        for(int i =0;i<classes.size();i++){
            ps.println("**************************************************");
            ps.println(classes.get(i) + "." + methods.get(i) + "." + apis.get(i));
            ps.println("***************************************************");
            System.out.println("**************************************************");
            System.out.println(classes.get(i)+"."+methods.get(i)+"."+apis.get(i));
            System.out.println("**************************************************");
            SootMethod sootMethod = (SootMethod) dict_methodName_method.get(methods.get(i));
            /*
            Iterate over each method of filtered class
             */
            if(sootMethod != null)
            {
                Body b = sootMethod.retrieveActiveBody();
                UnitGraph unitGraph = new ExceptionalUnitGraph(b);
                ArrayList<Dictionary> paraLabels = new ArrayList();
                refLocals = new HashSet<>();
                Iterator localIt = b.getLocals().iterator();
                /*store locals(normal as well as stack variables)  of each method in refLocals */
                while (localIt.hasNext())
                {
                    Local l = (Local) localIt.next();
                    System.out.println(l.getName());
                    refLocals.add(l.toString());
                }
                StatementHanding statementHanding = new StatementHanding(classes.get(i),methods.get(i),refLocals);
                InformationFlowAnalysis informationFlowAnalysis = new InformationFlowAnalysis(unitGraph, labelManager, subLabel, convertClass(classes.get(i)), methods.get(i),paraLabels,statementHanding);

                informationFlowAnalysis.iterateOverGraph();

            }
        }
    }
    
    /*
    Converts
            : Landroid/location/Location;-> android.location.Location
     */
    
    public static String convertClass(String str){ 
        String str1 = str.split("L",2)[1];
        String str2 = str1.replace('/','.');
        str1 =str2.split(";",2)[0];
        return str1;
    }
    /*
    Create public label(appPackageName, {appPackageName, public}, {appPackageName})
     */
    
    public static Dictionary createPublicLabel(String obj_id){
        Dictionary ret;
        makeRWLabel = new MakeRWLabel();
        HashSet<String> subOwner = new HashSet<>();
        subOwner.add(appPackageName);
        HashSet<String> readers = new HashSet<>();
        readers.add(appPackageName);
        readers.add("public");

        ret = makeRWLabel.makeSubLabel(subOwner,readers);
        labelManager = new LabelManager();
        labelManager.saveLabel(obj_id,ret);
        return ret;
    }
    
    /*create subJect Label publicLabel with S1 as owner */
    public static void createSubjectLabel(){
        
        subLabel = createPublicLabel("S1"); // subjectLevel(packageName,packageName,packageName)
        
    }
    public static String trimMethods(String str){
       return str.split("\\(",2)[0];
    }
    public static String trimApiMethod(String str){
       return str.split("\\(",2)[0]; 
    }
    public static void printLabel(String var, String className, String methodName){
//        String obj_id = createObjId(var,className,methodName);
        System.out.println(var+" "+labelManager.getLabel(var,className,methodName));
    }
    
    /* given an id in simple variable name 
    check if it has label defined 
        update label with sublabel
    else
        create label with given sublabel
     */
    
    public static void checkAndUpdate(String _id,String className,String methodName){
        String obj_id = createObjId(_id,className,methodName);
        if(checkAndDef(_id,className,methodName))
        {
            labelManager.updateLabel(obj_id, subLabel);
//            System.out.println("checkAndDef "+subLabel);
        }
        else{
            System.out.println("label created");
        }
    }
    
    /*
    create privateLabel(appPackageName, {appPackageName},{appPackageName})
     */
    public static boolean checkPrivateField(String obj_id,String className,String methodName){
        if(((Set)labelManager.getLabel(obj_id,className,methodName).get("writers")).size()>1)
            return true;
        return false;
    }
    /*given  _id of variable in simple name check if its label defined otherwise initialize the variable label as publicLabel
    
     */
    
    public static boolean checkAndDef(String _id, String className, String methodName)
    {
        if(_id == null || _id == "0" || _id == "true" || _id == "false")
            return false;
        Dictionary lolabel = labelManager.getLabel(_id, className, methodName);
        System.out.println("Check and Def "+_id+" "+lolabel);
        if (lolabel == null)
        {
            Dictionary publicLabel = createPublicLabel("dummyLabel"+className+methodName);
            new RWLabel().createObjLabel(publicLabel,_id,labelManager,className,methodName);
//      labelManager.saveLabel(obj_id,objLabel,className,methodName);
            return false;
        }
        return true;
    }
    /* maintaining local private fields assigned to a variable 
           $r1.cacad = acdadfa;
           <$r1, cacad>
           
    
    */
    
    public static boolean addLocalToField(String key, String value){
        if(fieldsLocals.containsKey(key)){
            if(!fieldsLocals.get(key).contains(value))
                fieldsLocals.get(key).add(value);
            return true;
        }
        else
        {
            HashSet hashSet = new HashSet();
            hashSet.add(value);
//            System.out.println("adding it to fields locals"+fieldsLocals);
            fieldsLocals.put(key, hashSet);
//            System.out.println("adding it to fields locals"+fieldsLocals);

        }
        
        return false;
    }
    
    /*given a _id return its absolute id( id+className+methodName) */
    
    public static String createObjId(String obj_id,String className,String methodName){
        return className+"."+methodName+"."+obj_id;
    }
    
}
