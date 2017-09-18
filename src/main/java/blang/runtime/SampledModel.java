package blang.runtime;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.objenesis.strategy.StdInstantiatorStrategy;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Kryo.DefaultInstantiatorStrategy;

import blang.algo.AnnealedParticle;
import blang.algo.TemperedParticle;
import blang.core.AnnealedFactor;
import blang.core.Factor;
import blang.core.ForwardSimulator;
import blang.core.LogScaleFactor;
import blang.core.Model;
import blang.core.Param;
import blang.inits.experiments.tabwriters.TidySerializer;
import blang.mcmc.BuiltSamplers;
import blang.mcmc.ExponentiatedFactor;
import blang.mcmc.Sampler;
import blang.runtime.objectgraph.AnnealingStructure;
import blang.runtime.objectgraph.GraphAnalysis;
import blang.runtime.objectgraph.ObjectNode;
import blang.types.RealScalar;
import briefj.BriefLists;
import briefj.BriefLog;
import briefj.ReflexionUtils;

public class SampledModel implements AnnealedParticle, TemperedParticle
{
  public final Model model;
  private final List<Sampler> posteriorInvariantSamplers;
  private List<ForwardSimulator> forwardSamplers;
  private final RealScalar annealingExponent;
  
  //// Various caches to make it quick to compute the global density
  
  /*
   * Factors that can be updated lazily. Contains those in (1) AnnealingStructure.fixedLogScaleFactors 
   * (enclosing them in a trivial ExponentiatedFactor if necessary)
   * and (2) AnnealingStructure.exponentiatedFactors.
   * 
   * Excludes otherAnnealedFactors which need to be all computed explicitly at all times.
   */
  private final List<ExponentiatedFactor> sparseUpdateFactors; 
  
  /*
   * Indices for (1) and (2) described above
   */
  private final ArrayList<Integer> sparseUpdateFixedIndices = new ArrayList<>();    // (1)
  private final ArrayList<Integer> sparseUpdateAnnealedIndices = new ArrayList<>(); // (2)
  
  // TODO: make sure the index-based data structures are shallowly cloned
  // sampler index -> factor indices (using array since inner might have lots of small arrays)
  private final int [][] 
      sampler2sparseUpdateFixed,    // (1)
      sampler2sparseUpdateAnnealed; // (2)
  
  private final double [] caches;
  private double sumPreannealedFiniteDensities, sumFixedDensities;
  private int nOutOfSupport;
  
  /*
   * Those need to be recomputed each time
   */
  private final List<AnnealedFactor> otherAnnealedFactors;
  
  private List<Integer> currentSamplingOrder = null;
  private int currentPosition = -1;
  
  private final Map<String, Object> objectsToOutput;
  
  public SampledModel(GraphAnalysis graphAnalysis, BuiltSamplers samplers, Random initRandom) 
  {
    this.model = graphAnalysis.model;
    this.posteriorInvariantSamplers = samplers.list;
    this.forwardSamplers = graphAnalysis.createForwardSimulator();
    AnnealingStructure annealingStructure = graphAnalysis.createLikelihoodAnnealer();
    this.annealingExponent = annealingStructure.annealingParameter;
    
    otherAnnealedFactors = annealingStructure.otherAnnealedFactors;
    
    sparseUpdateFactors = initSparseUpdateFactors(annealingStructure);
    caches = new double[sparseUpdateFactors.size()];
    
    sampler2sparseUpdateAnnealed = new int[samplers.list.size()][];
    sampler2sparseUpdateFixed = new int[samplers.list.size()][];
    initSampler2FactorIndices(graphAnalysis, samplers, annealingStructure);
    
    Set<ExponentiatedFactor> exponentiatedFactorsSet = new HashSet<>(annealingStructure.exponentiatedFactors);
    for (int i = 0; i < sparseUpdateFactors.size(); i++)
      (exponentiatedFactorsSet.contains(sparseUpdateFactors.get(i)) ? sparseUpdateAnnealedIndices : sparseUpdateFixedIndices).add(i);
    
    this.objectsToOutput = new LinkedHashMap<String, Object>();
    for (Field f : ReflexionUtils.getDeclaredFields(model.getClass(), true)) 
      if (f.getAnnotation(Param.class) == null) // TODO: filter out fully observed stuff too
        objectsToOutput.put(f.getName(), ReflexionUtils.getFieldValue(f, model));
    
    forwardSample(initRandom); 
  }
  
  public int nPosteriorSamplers()
  {
    return posteriorInvariantSamplers.size();
  }
  
  public double logDensity()
  {
    final double exponentValue = annealingExponent.doubleValue();
    return 
      sumOtherAnnealed() 
        + sumFixedDensities 
        + exponentValue * sumPreannealedFiniteDensities
        // ?: to avoid 0 * -INF
        + (nOutOfSupport == 0 ? 0.0 : nOutOfSupport * ExponentiatedFactor.annealedMinusInfinity(exponentValue));
  }
  
  @Override
  public double logDensity(double temperingParameter) 
  {
    final double previousValue = annealingExponent.doubleValue();
    annealingExponent.set(temperingParameter);
    final double result = logDensity();
    annealingExponent.set(previousValue);
    return result;
  }
  
  @Override
  public double logDensityRatio(double temperature, double nextTemperature) 
  {
    double otherAnnealedDiff = 0.0;
    if (!otherAnnealedFactors.isEmpty())
    {
      final double currentExp = getExponent();
      
      annealingExponent.set(nextTemperature);
      otherAnnealedDiff += sumOtherAnnealed();
      annealingExponent.set(temperature);
      otherAnnealedDiff -= sumOtherAnnealed();
      
      annealingExponent.set(currentExp);
    }
    
    double delta = nextTemperature - temperature;
    return
      otherAnnealedDiff
        + delta * sumPreannealedFiniteDensities
        // ?: to avoid 0 * -INF
        + (nOutOfSupport == 0 ? 
            0.0 : 
            nOutOfSupport * (ExponentiatedFactor.annealedMinusInfinity(nextTemperature) - ExponentiatedFactor.annealedMinusInfinity(temperature)));
  }
  
  private double sumOtherAnnealed()
  {
    double sum = 0.0;
    for (AnnealedFactor factor : otherAnnealedFactors)
      sum += factor.logDensity();
    return sum;
  }
  
  private static ThreadLocal<Kryo> duplicator = new ThreadLocal<Kryo>()
  {
    @Override
    protected Kryo initialValue() 
    {
      Kryo kryo = new Kryo();
      DefaultInstantiatorStrategy defaultInstantiatorStrategy = new Kryo.DefaultInstantiatorStrategy();
      defaultInstantiatorStrategy.setFallbackInstantiatorStrategy(new StdInstantiatorStrategy());
      kryo.setInstantiatorStrategy(defaultInstantiatorStrategy);
      kryo.getFieldSerializerConfig().setCopyTransient(false); 
      return kryo;
    }
  };
  
  public SampledModel duplicate() 
  {
    return duplicator.get().copy(this);
  }
  
  public void posteriorSamplingStep(Random random, int kernelIndex)
  {
    posteriorInvariantSamplers.get(kernelIndex).execute(random);  
    update(kernelIndex);
  }
  
  public void posteriorSamplingStep_deterministicScanAndShuffle(Random random)
  {
    if (posteriorInvariantSamplers.isEmpty()) 
    {
      BriefLog.warnOnce("WARNING: no posterior sampler defined");
      return;
    }
    if (currentSamplingOrder == null)
      currentSamplingOrder = new ArrayList<>(BriefLists.integers(nPosteriorSamplers()).asList());
    if (currentPosition == -1)
    {
      Collections.shuffle(currentSamplingOrder, random);
      currentPosition = nPosteriorSamplers() - 1;
    }
    int samplerIndex = currentSamplingOrder.get(currentPosition--);
    posteriorSamplingStep(random, samplerIndex);
  }
  
  public void forwardSample(Random random)
  {
    for (ForwardSimulator sampler : forwardSamplers) 
      sampler.generate(random); 
    updateAll();
  }
  
  /**
   * Performance optimization: once the forward simulator 
   * not needed, dropping it speeds up cloning. Useful for 
   * Change of Measure algorithms, but not for tempering algorithms.
   */
  public void dropForwardSimulator()
  {
    this.forwardSamplers = null;
  }
  
  public void setExponent(double value)
  {
    annealingExponent.set(value);
  }
  
  public double getExponent()
  {
    return annealingExponent.doubleValue(); 
  }
  
  public static class SampleWriter
  {
    final Map<String, Object> objectsToOutput;
    final TidySerializer serializer;
    public SampleWriter(Map<String, Object> objectsToOutput, TidySerializer serializer) 
    {
      this.objectsToOutput = objectsToOutput;
      this.serializer = serializer; 
    }
    public void write(@SuppressWarnings("unchecked") org.eclipse.xtext.xbase.lib.Pair<Object,Object> ... sampleContext)
    {
      for (Entry<String,Object> entry : objectsToOutput.entrySet()) 
        serializer.serialize(entry.getValue(), entry.getKey(), sampleContext);
    }
  }
  
  public SampleWriter getSampleWriter(TidySerializer serializer)
  {
    return new SampleWriter(objectsToOutput, serializer);
  }
  
  //// Cache management
  
  private void updateAll()
  {
    sumFixedDensities = 0.0;
    for (int fixedIndex : sparseUpdateFixedIndices)
    {
      double newCache = sparseUpdateFactors.get(fixedIndex).logDensity();
      sumFixedDensities += newCache;
      caches[fixedIndex] = newCache;
    }
    
    sumPreannealedFiniteDensities = 0.0;
    nOutOfSupport = 0;
    for (int annealedIndex : sparseUpdateAnnealedIndices)
    {
      double newPreAnnealedCache = sparseUpdateFactors.get(annealedIndex).enclosed.logDensity();
      caches[annealedIndex] = newPreAnnealedCache;
      
      if (newPreAnnealedCache == Double.NEGATIVE_INFINITY)
        nOutOfSupport++;
      else
        sumPreannealedFiniteDensities += newPreAnnealedCache;
    }
  }
  
  private void update(int samplerIndex)
  {
    if (sumPreannealedFiniteDensities == Double.NEGATIVE_INFINITY || sumFixedDensities == Double.NEGATIVE_INFINITY)
    {
      System.err.println("WARNING: forward simulation generated a particle of probability zero. This could happen infrequently due to numerical precision but could lead to performance problems if it happens frequently (e.g. due to determinism in likelihood).");
      updateAll();
      return;
    }
    
    for (int fixedIndex : sampler2sparseUpdateFixed[samplerIndex])
    {
      double newCache = sparseUpdateFactors.get(fixedIndex).logDensity();
      sumFixedDensities += newCache - caches[fixedIndex];
      caches[fixedIndex] = newCache;
    }
    
    for (int annealedIndex : sampler2sparseUpdateAnnealed[samplerIndex])
    {
      {
        double oldPreAnneledCache = caches[annealedIndex];
        
        if (oldPreAnneledCache == Double.NEGATIVE_INFINITY)
          nOutOfSupport--;
        else
          sumPreannealedFiniteDensities -= oldPreAnneledCache;
      }
      
      {
        double newPreAnnealedCache = sparseUpdateFactors.get(annealedIndex).enclosed.logDensity();
        caches[annealedIndex] = newPreAnnealedCache;
        
        if (newPreAnnealedCache == Double.NEGATIVE_INFINITY)
          nOutOfSupport++;
        else
          sumPreannealedFiniteDensities += newPreAnnealedCache;
      }
    }
  }
  
  //// Utility methods setting up caches
  
  private void initSampler2FactorIndices(GraphAnalysis graphAnalysis, BuiltSamplers samplers, AnnealingStructure annealingStructure) 
  {
    Map<ExponentiatedFactor, Integer> factor2Index = factor2index(sparseUpdateFactors);
    Set<ExponentiatedFactor> annealedFactors = new HashSet<>(annealingStructure.exponentiatedFactors);
    for (int samplerIndex = 0; samplerIndex < samplers.list.size(); samplerIndex++)
    {
      ObjectNode<?> sampledVariable = samplers.correspondingVariables.get(samplerIndex);
      List<? extends Factor> factors = 
          graphAnalysis.getConnectedFactor(sampledVariable).stream()
            .map(node -> node.object)
            .collect(Collectors.toList());
      List<Integer> 
        annealedIndices = new ArrayList<>(),
        fixedIndices = new ArrayList<>();
      for (Factor f : factors)
        (annealedFactors.contains(factors) ? annealedIndices : fixedIndices).add(factor2Index.get(f));
      sampler2sparseUpdateAnnealed[samplerIndex] = annealedIndices.stream().mapToInt(i->i).toArray();
      sampler2sparseUpdateFixed   [samplerIndex] = fixedIndices   .stream().mapToInt(i->i).toArray();
    }
  }

  private static Map<ExponentiatedFactor, Integer> factor2index(List<ExponentiatedFactor> factors) 
  {
    Map<ExponentiatedFactor, Integer> result = new IdentityHashMap<>();
    for (int i = 0; i < factors.size(); i++)
      result.put(factors.get(i), i);
    return result;
  }

  /**
   * Ignore factors that are not LogScaleFactor's (e.g. constraints), make sure everything else are AnnealedFactors.
   */
  private static List<ExponentiatedFactor> initSparseUpdateFactors(AnnealingStructure structure) 
  {
    ArrayList<ExponentiatedFactor> result = new ArrayList<>();
    result.addAll(structure.exponentiatedFactors);
    for (LogScaleFactor f : structure.fixedLogScaleFactors)
    {
      if (!(f instanceof ExponentiatedFactor))
        f = new ExponentiatedFactor(f);
      result.add((ExponentiatedFactor) f);
    }
    return result;
  }
}