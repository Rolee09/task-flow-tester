 /*******************************************************************************
  Copyright: see readme.txt

  $revision_history$
  09-jan-2013   Wilfred van der Deijl
  1.3           read XML with XSL for backwards compatibility
  03-jan-2013   Wilfred van der Deijl
  1.2           upgrade JAXB v1 to v2.1
  17-dec-2012   Steven Davelaar
  1.1           Added sorting
  06-jun-2012   Steven Davelaar
  1.0           initial creation
 ******************************************************************************/
 package org.emg.adf.tftester.rt.model;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.StringWriter;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.faces.context.FacesContext;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.stream.StreamSource;

import oracle.adf.share.logging.ADFLogger;

import oracle.jbo.JboException;

import org.emg.adf.tftester.rt.model.xml.jaxb.ObjectFactory;
import org.emg.adf.tftester.rt.model.xml.jaxb.ParamValueObject;
import org.emg.adf.tftester.rt.model.xml.jaxb.TaskFlowTester;
import org.emg.adf.tftester.rt.model.xml.jaxb.TestCase;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;


/**
 * The service class that maintains the list of task flows and testcases, loads available task flows,
 * and implements the XML import/export functions.
 */
 public class TaskFlowTesterService implements Serializable
 {
   @SuppressWarnings("compatibility:-3144222963419312295")
   private static final long serialVersionUID = 1L;
   private List<TaskFlow> testTaskFlows = new ArrayList<TaskFlow>();
   private Map<String, TaskFlow> testTaskFlowsMap = new HashMap<String,TaskFlow>();
   private static ADFLogger sLog = ADFLogger.createADFLogger(TaskFlowTesterService.class);

   public TaskFlowTesterService()
   {
     super();
     loadDefaultTestcases();
   }

   public TaskFlow addTaskFlow(String taskFlowId, String displayName, boolean throwError, boolean returnExisting)
   {
     boolean exists = testTaskFlowsMap.containsKey(taskFlowId);
     if (exists)
     {
       if (returnExisting)
       {
         return testTaskFlowsMap.get(taskFlowId);
       }
       else if (throwError)
       {
         throw new JboException("Task flow with this id already exists.");
       }
       else
       {
         return null;
       }
     }
     TaskFlow tf = null;
     try
     {
       tf = new TaskFlow();
       tf.setTaskFlowIdString(taskFlowId);
       tf.getTaskFlowDefinition();
       tf.setDisplayName(displayName);
       // call getDisplayName as final test, will throw NPE when task flow path is OK but id after # is wrong
       tf.getDisplayName();
       getTestTaskFlows().add(tf);
       testTaskFlowsMap.put(taskFlowId, tf);
     }
     catch (Exception e)
     {
       if (throwError)
       {
         throw new JboException("Invalid task flow id");
       }
     }
     return tf;
   }

   public List<TaskFlow> getTestTaskFlows()
   {
     return testTaskFlows;
   }

   public String exportToXML()
   {
     return writeToXML(createJaxbModelFromBeanModel());
   }

   public void importFromXml(Source xml)
   {
     try
     {
       TaskFlowTester tftester = createJaxbModelFromXml(xml);
       createBeanModelFromJaxbModel(tftester);
     }
     catch (Exception exc)
     {
       sLog.severe("error importing XML", exc);
       throw new JboException("Error importing testcases from XML: " +
                              exc.getMessage());
     }
     // create bean model from jaxbmodel
   }

   private void createBeanModelFromJaxbModel(TaskFlowTester tftester)
   {
     for (org.emg.adf.tftester.rt.model.xml.jaxb.TaskFlow jaxbTf: tftester.getTaskFlow())
     {
         //      TaskFlow tf = new TaskFlow();
         //      tf.setDisplayName(jaxbTf.getDisplayName());
         //      tf.setTaskFlowIdString(jaxbTf.getTaskFlowId());
         //      getTestTaskFlows().add(tf);
         // last argument is true so addTaskFlow will return existing task flow if present, so new testcases
         // will be added under existing node
       TaskFlow tf = addTaskFlow(jaxbTf.getTaskFlowId(), jaxbTf.getDisplayName(), false, true);
       if (tf==null)
       {
         // this happens when XML contains tf that no longer exists in context of this app
         sLog.warning("Task flow definition "+jaxbTf.getTaskFlowId()+" no longer exists or is not available in this application");
         continue;
       }
       // loop over TF testcases
       for (TestCase jaxbTc: jaxbTf.getTestCase())
       {
         TaskFlowTestCase tc = new TaskFlowTestCase(tf);
         tf.addTestCase(tc);
         tc.setDescription(jaxbTc.getDescription());
         tc.setName(jaxbTc.getName());
         tc.setRunAscall(jaxbTc.isRunAscall());
         tc.setRunInRegion(jaxbTc.isRunInRegion());
         tc.setStretchLayout(jaxbTc.isStretchLayout());
         // loop over param values
         if (jaxbTc.getParamValueObject() != null)
         {
           for (ParamValueObject jaxbVo: jaxbTc.getParamValueObject())
           {
             // check whether param name still exists in TF, if not ignore it.
             if (tf.getInputParameter(jaxbVo.getName())==null)
             {
               // input paramter no longer exists in TF, skip it
               sLog.warning("Input parameter "+jaxbVo.getName()+" no longer exists in "+tf.getTaskFlowIdString());
               continue;
             }
             ValueObject vo =
               new ValueObject(jaxbVo.getName(), jaxbVo.getClassName(),
                               null, false);
             tc.getParamValueObjects().add(vo);
             String value = jaxbVo.getValueAsString();
             if (value != null && !"".equals(value))
             {
               vo.setValueAsString(value);
               if (value.startsWith("#{"))
               {
                 vo.setElExpressionUsed(true);
               }
             }
             // add  detail param values
             addNestedValueObjects(vo, jaxbVo);
           }
         }
       }
       Collections.sort(tf.getTestCases());
     }
   }

   private void addNestedValueObjects(ValueObject vo,
                                      ParamValueObject jaxbVo)
   {
     if (jaxbVo.getParamValueObject() != null)
     {
       for (ParamValueObject jaxbDetailVo: jaxbVo.getParamValueObject())
       {
         ValueObject detailVo =
           new ValueObject(jaxbDetailVo.getName(), jaxbDetailVo.getClassName(),
                           vo, vo.isMapType());
         vo.getValueProperties().add(detailVo);
         String value = jaxbDetailVo.getValueAsString();
         if (value != null && !"".equals(value))
         {
           detailVo.setValueAsString(value);
           if (value.startsWith("#{"))
           {
             detailVo.setElExpressionUsed(true);
           }
         }
         // recursive call to add  detail param values
         addNestedValueObjects(detailVo, jaxbDetailVo);
       }
     }
   }

  private TaskFlowTester createJaxbModelFromXml(Source xml)
    throws JAXBException, TransformerException
  {
    String instancePath = TaskFlowTester.class.getPackage().getName();
    JAXBContext jc = JAXBContext.newInstance(instancePath);
    sLog.fine("JAXBContext: " + jc.getClass());

    // setup XSL transform for backwards compatibility
    final InputStream xslStream =
      Thread.currentThread().getContextClassLoader().getResourceAsStream("/org/emg/adf/tftester/rt/model/xml/attr-to-elem.xsl");
    if (xslStream == null)
    {
      throw new IllegalStateException("failed to load XSL resource");
    }
    Source xslSrc = new StreamSource(xslStream);
    TransformerFactory factory = TransformerFactory.newInstance();
    Transformer transformer = factory.newTransformer(xslSrc);

    // read xml while applying XSL
    DOMResult domRes = new DOMResult();
    transformer.transform(xml, domRes);
    Document doc = (Document) domRes.getNode();

    // unmarshal transformed xml doc to bean
    Unmarshaller u = jc.createUnmarshaller();
    TaskFlowTester tftester = (TaskFlowTester) u.unmarshal(doc);
    return tftester;
  }

   private TaskFlowTester createJaxbModelFromBeanModel()
   {
     ObjectFactory factory = new ObjectFactory();
     TaskFlowTester root = factory.createTaskFlowTester();
     for (TaskFlow tf: getTestTaskFlows())
     {
       org.emg.adf.tftester.rt.model.xml.jaxb.TaskFlow jaxbTf = factory.createTaskFlow();
       jaxbTf.setDisplayName(tf.getDisplayName());
       jaxbTf.setTaskFlowId(tf.getTaskFlowIdString());
       root.getTaskFlow().add(jaxbTf);
       // loop over TF testcases
       for (TaskFlowTestCase tc: tf.getTestCases())
       {
         TestCase jaxbTc = factory.createTestCase();
         jaxbTc.setDescription(tc.getDescription());
         jaxbTc.setName(tc.getName());
         jaxbTc.setRunAscall(tc.isRunAscall());
         jaxbTc.setRunInRegion(tc.isRunInRegion());
         jaxbTc.setStretchLayout(tc.isStretchLayout());
         jaxbTf.getTestCase().add(jaxbTc);
         // loop over param values
         if (tc.getParamValueObjects() != null)
         {
           for (ValueObject vo: tc.getParamValueObjects())
           {
             ParamValueObject jaxbVo = factory.createParamValueObject();
             jaxbVo.setName(vo.getName());
             jaxbVo.setClassName(vo.getClassName());
             jaxbVo.setValueAsString(vo.getValueAsString());
             // add  detail param values
             addNestedParamValues(factory, vo, jaxbVo);
             // add vo to testcase
             jaxbTc.getParamValueObject().add(jaxbVo);
           }
         }
       }
     }
     return root;
   }

   private void addNestedParamValues(ObjectFactory factory, ValueObject vo,
                                     ParamValueObject jaxbVo)
   {
     if (vo.getValueProperties() != null)
     {
       for (ValueObject detailVo: vo.getValueProperties())
       {
         ParamValueObject jaxbDetailVo =
           factory.createParamValueObject();
         jaxbDetailVo.setName(detailVo.getName());
         jaxbDetailVo.setClassName(detailVo.getClassName());
         jaxbDetailVo.setValueAsString(detailVo.getValueAsString());
         // recursive call to add  detail param values
         addNestedParamValues(factory, detailVo, jaxbDetailVo);
         // add vo to parent
         jaxbVo.getParamValueObject().add(jaxbDetailVo);
       }
     }
   }

   private String writeToXML(TaskFlowTester taskFlows)
   {
     String output = null;
     String instancePath = TaskFlowTester.class.getPackage().getName();
     try
     {
       JAXBContext jc = JAXBContext.newInstance(instancePath);
       sLog.fine("JAXBContext: " + jc.getClass());
       Marshaller m = jc.createMarshaller();
       m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
// javax.xml.bind.context.factory = org.eclipse.persistence.jaxb.JAXBContextFactory
//       m.setProperty("javax.xml.bind.context.factory", "org.eclipse.persistence.jaxb.JAXBContextFactory");
       sLog.fine("Marshaller: " + m.getClass());
       String encoding = "UTF-8";
       m.setProperty(Marshaller.JAXB_ENCODING, encoding);

       // format
       //      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
       //      dbf.setNamespaceAware(false);
       //      DocumentBuilder db = dbf.newDocumentBuilder();
       //        XMLDocument doc = (XMLDocument) db.newDocument();
       //        m.marshal(service, doc); // new FileOutputStream("c:/temp/appstruct-test.xml"));
       //
       //        XmlFormatter xf = new XmlFormatter();
       //        output = xf.format(doc,"1.0",encoding);
       //      }
       //      else
       //      {
       StringWriter sw = new StringWriter();
       m.marshal(taskFlows,
                 sw); // new FileOutputStream("c:/temp/appstruct-test.xml"));
       output = sw.toString();
       //      }

     }
     catch (Exception e)
     {
       e.printStackTrace();
     }
     return output;
   }

   /**
    * Load all task flows and testcases that are defined in adf-emg-task-flow-tester.xml on the classpath.
    * If multiple files are found, they will all be loaded, unless the task flow or testcase is already in the list.
    */
   private void loadDefaultTestcases()
   {
     try
     {
       Enumeration<URL> enump =
         Thread.currentThread().getContextClassLoader().getResources("adf-emg-task-flow-tester.xml");
       while (enump.hasMoreElements())
       {
         try
         {
           URL url = enump.nextElement();
           InputStream stream = url.openStream();
           importFromXml(new StreamSource(stream));
         }
         catch (IOException e)
         {
           // we catch IO exception here as well, so we go to next resource
         }
       }
     }
     catch (IOException e)
     {
     }
     Collections.sort(getTestTaskFlows());
   }

   public static void main(String[] args)
   {
     TaskFlowTesterService s = new TaskFlowTesterService();
     s.loadDefaultTestcases();
     System.err.println(s.exportToXML());
   }

   public void loadAvailableTaskFlows(String startDir, boolean recurseInSubDirs,
                                 boolean searchAdfLibs)
   {
     List<String> taskFlows = new ArrayList<String>();
     addLocalTaskFlows(taskFlows, startDir, recurseInSubDirs);
     if (searchAdfLibs)
     {
       addAdfLibraryTaskFlows(taskFlows);
     }
     // loop over discovered task flow id's, and call addTaskFlow
     // In addTaskFlow we check whether the atask flow is already added
     for (String taskFlowId : taskFlows)
     {
       addTaskFlow(taskFlowId, null, false, false);
     }
     Collections.sort(getTestTaskFlows());
   }

   private void addAdfLibraryTaskFlows(List<String> taskflows)
   {
     try
     {
       DocumentBuilderFactory dbFactory =
         DocumentBuilderFactory.newInstance();
       DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
       Enumeration<URL> enump =
         Thread.currentThread().getContextClassLoader().getResources("META-INF/task-flow-registry.xml");
       while (enump.hasMoreElements())
       {
         try
         {
           URL url = enump.nextElement();
           InputStream is = url.openStream();
           Document doc = dBuilder.parse(is);
           NodeList tfList =
             doc.getDocumentElement().getElementsByTagName("task-flow-descriptor");
           for (int i = 0; i < tfList.getLength(); i++)
           {
             Element tf = (Element) tfList.item(i);
             String path = tf.getAttribute("path");
             String id = tf.getAttribute("id");
             boolean testerTf = path!=null && path.startsWith("WEB-INF/adfemg/tftester");
             String tfId = path + "#" + id;
             String type = tf.getAttribute("type");
             String internal = tf.getAttribute("library-internal");
             if (!testerTf && "task-flow-definition".equals(type) &&
                 "false".equals(internal))
             {
               taskflows.add(tfId);
             }
           }
         }
         catch (SAXException e)
         {
           // ignore errors, continue with next tf registry file
         }
       }
     }
     catch (IOException e)
     {
       //
     }
     catch (ParserConfigurationException e)
     {
     }
   }

   public void addLocalTaskFlows(List<String> taskFlows, String startDir,
                                 boolean recursive)
   {
     Set<String> set =
       FacesContext.getCurrentInstance().getExternalContext().getResourcePaths(startDir);
     if (set==null)
     {
       return;
     }
     for (String resourcePath: set)
     {
       if (resourcePath.endsWith(".xml"))
       {
         addTaskFlowIdFromResourcePath(taskFlows, resourcePath);
       }
       else
       {
         try
         {
           URL url =
             FacesContext.getCurrentInstance().getExternalContext().getResource(resourcePath);
           File file = new File(url.toURI());
           if (file.isDirectory() && recursive)
           {
             addLocalTaskFlows(taskFlows, resourcePath, recursive);
           }
         }
         catch (MalformedURLException e)
         {
         }
         catch (URISyntaxException e)
         {
         }
       }
     }
   }

   private void addTaskFlowIdFromResourcePath(List<String> taskFlows,
                                              String resourcePath)
   {
     TaskFlowXMLHandler handler = null;
     try
     {
       InputStream is =
         FacesContext.getCurrentInstance().getExternalContext().getResourceAsStream(resourcePath);
       SAXParserFactory factory = SAXParserFactory.newInstance();
       SAXParser saxParser = factory.newSAXParser();
       handler = new TaskFlowXMLHandler();
       saxParser.parse(is, handler);
     }
     catch (ParserConfigurationException e)
     {
     }
     catch (SAXException e)
     {
     }
     catch (IOException e)
     {
     }
     if (handler != null && handler.getTaskFlowId() != null)
     {
       String path = resourcePath+"#"+handler.getTaskFlowId();
       taskFlows.add(path);
     }
   }

   /**
    * private class to parse an XML document and check whether it is a bounded task flow definition.
    * We use the Sax parser to increase performance and reduce memory usage because
    * we might very large xml files.
    */
   class TaskFlowXMLHandler
     extends DefaultHandler
   {
     int elcount = 0;
     String taskFlowId;

     public void startDocument()
     {
       elcount = 0;
       taskFlowId = null;
     }

     public String getTaskFlowId()
     {
       return taskFlowId;
     }

     public void startElement(String uri, String localName, String qName,
                              Attributes attributes)
       throws SAXException
     {
       elcount++;
       if (elcount == 2 && qName.equalsIgnoreCase("task-flow-definition"))
       {
         taskFlowId = attributes.getValue("", "id");
       }
       // task-flow-definition is second element, so always stop prcoessing now
       if (elcount == 2)
       {
         throw new SAXException("done");
       }
     }

     public void endElement(String uri, String localName, String qName)
       throws SAXException
     {

     }

     public void characters(char[] ch, int start, int length)
       throws SAXException
     {
     }
   }
 }
