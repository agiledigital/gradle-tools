package au.com.agiledigital.gradle.jacoco;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.IPackageCoverage;
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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;

/**
 * This class is responsible for opening an existing jacoco exec file and marking the target methods as 100% covered.
 * Its designed to remove the noise in coverage created due to machine (eclipse) generated methods (toString, hashcode,
 * etc). These methods are assumed to be good and the risk of them being wrong is far smaller than the impact of false
 * negatives in coverage reports of jacoco.
 */
public class JacocoMethodFilter {

   private final ExecutionDataStore store;

   private long startTimeMillis;

   private SessionInfo sessionInfo;

   public JacocoMethodFilter() {
      this.store = new ExecutionDataStore();
   }

   private URLClassLoader getTargetClassLoader(final Set<File> classDir,
                                               final Set<File> classPath) {
      URL[] urls = null;
      try {
         final List<URL> urlLists = new ArrayList<URL>();
         for (final File s : classDir) {
            urlLists.add(s.toURI().toURL());
         }

         for (final File s : classPath) {
            urlLists.add(s.toURI().toURL());
         }

         urls = urlLists.toArray(new URL[urlLists.size()]);
      }
      catch (final MalformedURLException e) {
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
   public void filter(final Collection<String> methodsToFilter,
                      final Set<File> classDirs,
                      final Set<File> classPaths) throws IOException {

      this.startTimeMillis = System.currentTimeMillis();

      final URLClassLoader urlClassLoader = this.getTargetClassLoader(classDirs, classPaths);

      for (final File classDir : classDirs) {
         final IBundleCoverage bundleCoverage = this.analyzeStructure(classDir);

         for (final IPackageCoverage packageCoverage : bundleCoverage.getPackages()) {
            for (final IClassCoverage classCoverage : packageCoverage.getClasses()) {

               final IRuntime runtime = new SystemPropertiesRuntime();

               try {
                  final Class<?> classToInstrument = urlClassLoader.loadClass(classCoverage.getName().replace("/", "."));
                  final String classAsPath = classCoverage.getName().replace('.', '/') + ".class";
                  final InputStream stream = classToInstrument.getClassLoader().getResourceAsStream(classAsPath);

                  final ClassReader reader = new ClassReader(stream);
                  final ClassWriter writer = new ClassWriter(reader, 0);

                  final LinkedList<Integer> setProbes = new LinkedList<Integer>();

                  final ClassInstrumenter instrumenter = new ClassInstrumenter(CRC64.checksum(reader.b), runtime,
                        writer) {

                     @Override
                     public MethodProbesVisitor visitMethod(final int access,
                                                            final String name,
                                                            final String desc,
                                                            final String signature,
                                                            final String[] exceptions) {
                        MethodProbesVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);

                        if (methodsToFilter.contains(name)) {

                           final MethodFilteringEmmitingMethodProbesVisitor filteringVisitor = new MethodFilteringEmmitingMethodProbesVisitor(
                                 setProbes, visitor);
                           visitor = filteringVisitor;
                        }

                        return visitor;
                     }
                  };

                  final ClassProbesAdapter visitor = new ClassProbesAdapter(instrumenter);
                  reader.accept(visitor, ClassReader.EXPAND_FRAMES);
                  final int numberOfProbes = visitor.nextId();

                  final ExecutionData executionData = this.store.get(classCoverage.getId(),
                                                                     classCoverage.getName(),
                                                                     numberOfProbes);
                  for (final Integer integer : setProbes) {
                     executionData.getProbes()[integer] = true;
                  }

               }
               catch (final ClassNotFoundException e) {
                  // Somethings we cant find. Ignore it
               }
            }
         }
      }
      urlClassLoader.close();

      this.sessionInfo = new SessionInfo("method-filter", this.startTimeMillis, System.currentTimeMillis());
   }

   private IBundleCoverage analyzeStructure(final File classesDirectory) throws IOException {
      final CoverageBuilder coverageBuilder = new CoverageBuilder();
      final ExecutionDataStore executionData = new ExecutionDataStore();
      final Analyzer analyzer = new Analyzer(executionData, coverageBuilder);
      analyzer.analyzeAll(classesDirectory);

      return coverageBuilder.getBundle(classesDirectory.getName());
   }

   public void save(final File execOut) throws IOException {
      final ExecutionDataWriter writer = new ExecutionDataWriter(new FileOutputStream(execOut));

      writer.visitSessionInfo(this.sessionInfo);

      for (final ExecutionData data : this.store.getContents()) {
         writer.visitClassExecution(data);
      }
   }

   public class MethodFilteringEmmitingMethodProbesVisitor extends MethodProbesVisitor {

      private final MethodProbesVisitor delegate;

      private final List<Integer> probesToSet;

      public MethodFilteringEmmitingMethodProbesVisitor(final List<Integer> probesToSet,
                                                        final MethodProbesVisitor delegate) {
         this.probesToSet = probesToSet;
         this.delegate = delegate;
      }

      @Override
      public void visitProbe(final int probeId) {
         this.delegate.visitProbe(probeId);
         this.probesToSet.add(probeId);
      }

      @Override
      public void visitJumpInsnWithProbe(final int opcode,
                                         final Label label,
                                         final int probeId) {
         this.delegate.visitJumpInsnWithProbe(opcode, label, probeId);
         this.probesToSet.add(probeId);
      }

      @Override
      public int hashCode() {
         return this.delegate.hashCode();
      }

      @Override
      public void visitInsnWithProbe(final int opcode,
                                     final int probeId) {
         this.delegate.visitInsnWithProbe(opcode, probeId);
         this.probesToSet.add(probeId);
      }

      @Override
      public void visitTableSwitchInsnWithProbes(final int min,
                                                 final int max,
                                                 final Label dflt,
                                                 final Label[] labels) {
         this.delegate.visitTableSwitchInsnWithProbes(min, max, dflt, labels);
      }

      @Override
      public boolean equals(final Object obj) {
         return this.delegate.equals(obj);
      }

      @Override
      public void visitLookupSwitchInsnWithProbes(final Label dflt,
                                                  final int[] keys,
                                                  final Label[] labels) {
         this.delegate.visitLookupSwitchInsnWithProbes(dflt, keys, labels);
      }

      @Override
      public AnnotationVisitor visitAnnotationDefault() {
         return this.delegate.visitAnnotationDefault();
      }

      @Override
      public AnnotationVisitor visitAnnotation(final String desc,
                                               final boolean visible) {
         return this.delegate.visitAnnotation(desc, visible);
      }

      @Override
      public AnnotationVisitor visitParameterAnnotation(final int parameter,
                                                        final String desc,
                                                        final boolean visible) {
         return this.delegate.visitParameterAnnotation(parameter, desc, visible);
      }

      @Override
      public void visitAttribute(final Attribute attr) {
         this.delegate.visitAttribute(attr);
      }

      @Override
      public void visitCode() {
         this.delegate.visitCode();
      }

      @Override
      public void visitFrame(final int type,
                             final int nLocal,
                             final Object[] local,
                             final int nStack,
                             final Object[] stack) {
         this.delegate.visitFrame(type, nLocal, local, nStack, stack);
      }

      @Override
      public String toString() {
         return this.delegate.toString();
      }

      @Override
      public void visitInsn(final int opcode) {
         this.delegate.visitInsn(opcode);
      }

      @Override
      public void visitIntInsn(final int opcode,
                               final int operand) {
         this.delegate.visitIntInsn(opcode, operand);
      }

      @Override
      public void visitVarInsn(final int opcode,
                               final int var) {
         this.delegate.visitVarInsn(opcode, var);
      }

      @Override
      public void visitTypeInsn(final int opcode,
                                final String type) {
         this.delegate.visitTypeInsn(opcode, type);
      }

      @Override
      public void visitFieldInsn(final int opcode,
                                 final String owner,
                                 final String name,
                                 final String desc) {
         this.delegate.visitFieldInsn(opcode, owner, name, desc);
      }

      @Override
      public void visitMethodInsn(final int opcode,
                                  final String owner,
                                  final String name,
                                  final String desc) {
         this.delegate.visitMethodInsn(opcode, owner, name, desc);
      }

      @Override
      public void visitInvokeDynamicInsn(final String name,
                                         final String desc,
                                         final Handle bsm,
                                         final Object... bsmArgs) {
         this.delegate.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
      }

      @Override
      public void visitJumpInsn(final int opcode,
                                final Label label) {
         this.delegate.visitJumpInsn(opcode, label);
      }

      @Override
      public void visitLabel(final Label label) {
         this.delegate.visitLabel(label);
      }

      @Override
      public void visitLdcInsn(final Object cst) {
         this.delegate.visitLdcInsn(cst);
      }

      @Override
      public void visitIincInsn(final int var,
                                final int increment) {
         this.delegate.visitIincInsn(var, increment);
      }

      @Override
      public void visitTableSwitchInsn(final int min,
                                       final int max,
                                       final Label dflt,
                                       final Label... labels) {
         this.delegate.visitTableSwitchInsn(min, max, dflt, labels);
      }

      @Override
      public void visitLookupSwitchInsn(final Label dflt,
                                        final int[] keys,
                                        final Label[] labels) {
         this.delegate.visitLookupSwitchInsn(dflt, keys, labels);
      }

      @Override
      public void visitMultiANewArrayInsn(final String desc,
                                          final int dims) {
         this.delegate.visitMultiANewArrayInsn(desc, dims);
      }

      @Override
      public void visitTryCatchBlock(final Label start,
                                     final Label end,
                                     final Label handler,
                                     final String type) {
         this.delegate.visitTryCatchBlock(start, end, handler, type);
      }

      @Override
      public void visitLocalVariable(final String name,
                                     final String desc,
                                     final String signature,
                                     final Label start,
                                     final Label end,
                                     final int index) {
         this.delegate.visitLocalVariable(name, desc, signature, start, end, index);
      }

      @Override
      public void visitLineNumber(final int line,
                                  final Label start) {
         this.delegate.visitLineNumber(line, start);
      }

      @Override
      public void visitMaxs(final int maxStack,
                            final int maxLocals) {
         this.delegate.visitMaxs(maxStack, maxLocals);
      }

      @Override
      public void visitEnd() {
         this.delegate.visitEnd();
      }

   }

   public static void main(final String[] args) {
      if (args.length < 3) {
         System.out.println("Usage: filter <out exec file> <classdirs comma seperated> <method list comma seperated>");
         System.exit(1);
      }

      final File jacocoOutExec = new File(args[0]);
      final List<String> classDirs = new ArrayList<String>();

      for (String method : args[1].split(",")) {
         method = method.trim();

         if (!method.isEmpty()) {
            classDirs.add(method);
         }
      }

      final List<String> filterMethods = new ArrayList<String>();

      for (String method : args[2].split(",")) {
         method = method.trim();

         if (!method.isEmpty()) {
            filterMethods.add(method);
         }
      }

      if (!jacocoOutExec.getParentFile().canWrite()) {
         System.out.println("Unable to write to jacoco exec file [" + jacocoOutExec + "]");
         System.exit(1);
      }

      final JacocoMethodFilter filter = new JacocoMethodFilter();

      try {
         // filter.filter(filterMethods, classDirs);
         filter.save(jacocoOutExec);
      }
      catch (final IOException e) {
         System.out.println("Unable to process exec file [" + e.getMessage() + "]");
         throw new RuntimeException(e);
      }
   }
}
