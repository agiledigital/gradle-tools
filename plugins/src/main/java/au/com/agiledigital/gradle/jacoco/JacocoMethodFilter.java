package au.com.agiledigital.gradle.jacoco;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.IPackageCoverage;
import org.jacoco.core.data.ExecFileLoader;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.internal.data.CRC64;
import org.jacoco.core.internal.flow.ClassProbesAdapter;
import org.jacoco.core.internal.flow.MethodProbesVisitor;
import org.jacoco.core.internal.instr.ClassInstrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.SystemPropertiesRuntime;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;

/**
 * This class is responsible for opening an existing jacoco exec file and marking
 * the target methods as 100% covered. Its designed to remove the noise in
 * coverage created due to machine (eclipse) generated methods (toString, hashcode, etc).
 * These methods are assumed to be good and the risk of them being wrong is far smaller
 * than the impact of false negatives in coverage reports of jacoco.
 */
public class JacocoMethodFilter {

   private File execFileToRead;

   private ExecFileLoader execInFileLoader;

   public JacocoMethodFilter(File execFileToRead) {
      this.execFileToRead = execFileToRead;

   }

   private URLClassLoader getTargetClassLoader(final List<String> classDir) {
      URL[] urls = null;
      try {
         List<URL> urlLists = new ArrayList<URL>();
         for (String s : classDir) {
            urlLists.add(new File(s).toURI().toURL());
         }
         urls = urlLists.toArray(new URL[urlLists.size()]);
      }
      catch (MalformedURLException e) {
      }

      return new URLClassLoader(urls);
   }

   /**
    * Process the exec file and filter out the listed methods. This will cause a new exec file to be created where the
    * listed methods lines are all marked as covered.
    * 
    * @param execFileToRead
    * @param methodsToFilter
    */
   public void filter(final List<String> methodsToFilter,
                      final List<String> classDirs) throws IOException {

      this.loadExecutionData();

      final ExecutionDataStore store = this.execInFileLoader.getExecutionDataStore();

      URLClassLoader urlClassLoader = this.getTargetClassLoader(classDirs);

      for (String classDir : classDirs) {
         final IBundleCoverage bundleCoverage = analyzeStructure(new File(classDir));

         for (IPackageCoverage packageCoverage : bundleCoverage.getPackages()) {
            for (final IClassCoverage classCoverage : packageCoverage.getClasses()) {
               IRuntime runtime = new SystemPropertiesRuntime();

               try {
                  Class classToInstrument = urlClassLoader.loadClass(classCoverage.getName().replace("/", "."));
                  String classAsPath = classCoverage.getName().replace('.', '/') + ".class";
                  InputStream stream = classToInstrument.getClassLoader().getResourceAsStream(classAsPath);

                  ClassReader reader = new ClassReader(stream);
                  ClassWriter writer = new ClassWriter(reader, 0);

                  ClassInstrumenter instrumenter = new ClassInstrumenter(CRC64.checksum(reader.b), runtime, writer) {

                     @Override
                     public MethodProbesVisitor visitMethod(int access,
                                                            String name,
                                                            String desc,
                                                            String signature,
                                                            String[] exceptions) {
                        MethodProbesVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
                        ExecutionData data = store.get(classCoverage.getId());

                        if (methodsToFilter.contains(name) && data != null) {
                           
                           visitor = new MethodFilteringEmmitingMethodProbesVisitor(data, visitor);
                        }

                        return visitor;
                     }
                  };

                  ClassVisitor visitor = new ClassProbesAdapter(instrumenter);
                  reader.accept(visitor, ClassReader.EXPAND_FRAMES);
               }
               catch (ClassNotFoundException e) {
                  // Somethings we cant find. Ignore it
               }
            }
         }
      }
      urlClassLoader.close();
   }

   private void loadExecutionData() throws IOException {
      this.execInFileLoader = new ExecFileLoader();
      this.execInFileLoader.load(this.execFileToRead);
   }

   private IBundleCoverage analyzeStructure(File classesDirectory) throws IOException {
      final CoverageBuilder coverageBuilder = new CoverageBuilder();
      ExecutionDataStore executionData = new ExecutionDataStore();
      final Analyzer analyzer = new Analyzer(executionData, coverageBuilder);
      analyzer.analyzeAll(classesDirectory);

      return coverageBuilder.getBundle(classesDirectory.getName());
   }

   public void save(File execOut) throws IOException {
      ExecutionDataWriter writer = new ExecutionDataWriter(new FileOutputStream(execOut));

      for (SessionInfo info : this.execInFileLoader.getSessionInfoStore().getInfos()) {
         writer.visitSessionInfo(info);
      }

      for (ExecutionData data : this.execInFileLoader.getExecutionDataStore().getContents()) {
         writer.visitClassExecution(data);
      }
   }

   public class MethodFilteringEmmitingMethodProbesVisitor extends MethodProbesVisitor {
      private MethodProbesVisitor delegate;

      private List<Integer> probeIds;

      private ExecutionData data;

      public void visitProbe(int probeId) {
         delegate.visitProbe(probeId);
         this.probeIds.add(probeId);
      }

      public void visitJumpInsnWithProbe(int opcode,
                                         Label label,
                                         int probeId) {
         delegate.visitJumpInsnWithProbe(opcode, label, probeId);
         this.probeIds.add(probeId);
      }

      public int hashCode() {
         return delegate.hashCode();
      }

      public void visitInsnWithProbe(int opcode,
                                     int probeId) {
         delegate.visitInsnWithProbe(opcode, probeId);
         this.probeIds.add(probeId);
      }

      public void visitTableSwitchInsnWithProbes(int min,
                                                 int max,
                                                 Label dflt,
                                                 Label[] labels) {
         delegate.visitTableSwitchInsnWithProbes(min, max, dflt, labels);
      }

      public boolean equals(Object obj) {
         return delegate.equals(obj);
      }

      public void visitLookupSwitchInsnWithProbes(Label dflt,
                                                  int[] keys,
                                                  Label[] labels) {
         delegate.visitLookupSwitchInsnWithProbes(dflt, keys, labels);
      }

      public AnnotationVisitor visitAnnotationDefault() {
         return delegate.visitAnnotationDefault();
      }

      public AnnotationVisitor visitAnnotation(String desc,
                                               boolean visible) {
         return delegate.visitAnnotation(desc, visible);
      }

      public AnnotationVisitor visitParameterAnnotation(int parameter,
                                                        String desc,
                                                        boolean visible) {
         return delegate.visitParameterAnnotation(parameter, desc, visible);
      }

      public void visitAttribute(Attribute attr) {
         delegate.visitAttribute(attr);
      }

      public void visitCode() {
         delegate.visitCode();
      }

      public void visitFrame(int type,
                             int nLocal,
                             Object[] local,
                             int nStack,
                             Object[] stack) {
         delegate.visitFrame(type, nLocal, local, nStack, stack);
      }

      public String toString() {
         return delegate.toString();
      }

      public void visitInsn(int opcode) {
         delegate.visitInsn(opcode);
      }

      public void visitIntInsn(int opcode,
                               int operand) {
         delegate.visitIntInsn(opcode, operand);
      }

      public void visitVarInsn(int opcode,
                               int var) {
         delegate.visitVarInsn(opcode, var);
      }

      public void visitTypeInsn(int opcode,
                                String type) {
         delegate.visitTypeInsn(opcode, type);
      }

      public void visitFieldInsn(int opcode,
                                 String owner,
                                 String name,
                                 String desc) {
         delegate.visitFieldInsn(opcode, owner, name, desc);
      }

      public void visitMethodInsn(int opcode,
                                  String owner,
                                  String name,
                                  String desc) {
         delegate.visitMethodInsn(opcode, owner, name, desc);
      }

      public void visitInvokeDynamicInsn(String name,
                                         String desc,
                                         Handle bsm,
                                         Object... bsmArgs) {
         delegate.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
      }

      public void visitJumpInsn(int opcode,
                                Label label) {
         delegate.visitJumpInsn(opcode, label);
      }

      public void visitLabel(Label label) {
         delegate.visitLabel(label);
      }

      public void visitLdcInsn(Object cst) {
         delegate.visitLdcInsn(cst);
      }

      public void visitIincInsn(int var,
                                int increment) {
         delegate.visitIincInsn(var, increment);
      }

      public void visitTableSwitchInsn(int min,
                                       int max,
                                       Label dflt,
                                       Label... labels) {
         delegate.visitTableSwitchInsn(min, max, dflt, labels);
      }

      public void visitLookupSwitchInsn(Label dflt,
                                        int[] keys,
                                        Label[] labels) {
         delegate.visitLookupSwitchInsn(dflt, keys, labels);
      }

      public void visitMultiANewArrayInsn(String desc,
                                          int dims) {
         delegate.visitMultiANewArrayInsn(desc, dims);
      }

      public void visitTryCatchBlock(Label start,
                                     Label end,
                                     Label handler,
                                     String type) {
         delegate.visitTryCatchBlock(start, end, handler, type);
      }

      public void visitLocalVariable(String name,
                                     String desc,
                                     String signature,
                                     Label start,
                                     Label end,
                                     int index) {
         delegate.visitLocalVariable(name, desc, signature, start, end, index);
      }

      public void visitLineNumber(int line,
                                  Label start) {
         delegate.visitLineNumber(line, start);
      }

      public void visitMaxs(int maxStack,
                            int maxLocals) {
         delegate.visitMaxs(maxStack, maxLocals);
      }

      public void visitEnd() {
         delegate.visitEnd();

         if (data != null) {
            for (int i : this.probeIds) {
               data.getProbes()[i] = true;
            }
         }
      }

      public MethodFilteringEmmitingMethodProbesVisitor(ExecutionData data,
                                                        MethodProbesVisitor delegate) {
         this.delegate = delegate;
         this.probeIds = new ArrayList<Integer>();
         this.data = data;
      }
   }

   public static void main(String[] args) {
      if (args.length < 4) {
         System.out.println("Usage: filter <in exec file> <out exec file> <classdirs> <method list comma seperated>");
         System.exit(1);
      }

      File jacocoInExec = new File(args[0]);
      File jacocoOutExec = new File(args[1]);
      List<String> classDirs = new ArrayList<String>();

      for (String method : args[2].split(",")) {
         method = method.trim();

         if (!method.isEmpty()) {
            classDirs.add(method);
         }
      }

      List<String> filterMethods = new ArrayList<String>();

      for (String method : args[3].split(",")) {
         method = method.trim();

         if (!method.isEmpty()) {
            filterMethods.add(method);
         }
      }

      if (!jacocoInExec.canRead()) {
         System.out.println("Unable to read jacoco exec file [" + jacocoInExec + "]");
         System.exit(1);
      }

      if (!jacocoOutExec.getParentFile().canWrite()) {
         System.out.println("Unable to write to jacoco exec file [" + jacocoOutExec + "]");
         System.exit(1);
      }

      JacocoMethodFilter filter = new JacocoMethodFilter(jacocoInExec);

      try {
         filter.filter(filterMethods, classDirs);
         filter.save(jacocoOutExec);
      }
      catch (IOException e) {
         System.out.println("Unable to process exec file [" + e.getMessage() + "]");
         throw new RuntimeException(e);
      }
   }
}
