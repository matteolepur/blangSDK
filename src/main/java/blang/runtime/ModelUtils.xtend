package blang.runtime

import blang.core.Factor
import java.util.Set
import blang.core.ModelComponent
import java.util.ArrayList
import java.util.HashSet
import java.util.List
import blang.core.Model
import java.util.LinkedList
import briefj.run.Results
import blang.mcmc.SamplerBuilder
import blang.runtime.objectgraph.GraphAnalysis
import blang.mcmc.Sampler
import java.lang.reflect.Field
import blang.runtime.objectgraph.Inputs
import blang.runtime.objectgraph.ObjectNode
import briefj.ReflexionUtils
import blang.core.Param

class ModelUtils {
  
  /**
   * Find recursively all factors defined by the input ModelComponent.
   */
  def static List<Factor> factors(ModelComponent root) {
    var LinkedList<ModelComponent> queue = new LinkedList
    queue.add(root) 
    var List<Factor> result = new ArrayList 
    var Set<ModelComponent> visited = new HashSet
    while (!queue.isEmpty()) {
      var ModelComponent current = queue.poll() 
      visited.add(current) 
      if (current instanceof Factor) 
        result.add(current as Factor) 
      if (current instanceof Model) {
        var Model model = current as Model 
        for (ModelComponent child : model.components()) 
          if (!visited.contains(child)) 
            queue.add(child) 
      }
    }
    return result 
  }
  
  def static GraphAnalysis graphAnalysis(Model model, Inputs inputs) {
    for (Factor f : factors(model)) 
      inputs.addFactor((f as Factor)) 
    // register the variables
    
    // Since we do not track variable names this way anymore, just register the model
    // directly, which will recursively take care of all the variables; this is 
    // **also important** if one wants to design a sampler for the outer-most model
    inputs.addVariable(model)
    
    // mark params as observed
    for (Field f : ReflexionUtils::getDeclaredFields(model.class, true)) {
      if (f.getAnnotation(Param) != null) {
        inputs.markAsObserved(ReflexionUtils::getFieldValue(f, model), true)
      }
    }
    
    // analyze the object graph
    return GraphAnalysis.create(inputs) 
  }
  
//  def static void visualizeGraphAnalysis(GraphAnalysis graphAnalysis, VariableNamingService namingService) {
//    // output visualization of the graph
//    graphAnalysis.accessibilityGraph.exportDot(Results.getFileInResultFolder("accessibility-graph.dot")) 
//    graphAnalysis.exportFactorGraphVisualization(Results.getFileInResultFolder("factor-graph.dot"), [node |
//      switch (node) {
//        ObjectNode<?> : {
//          val String name = namingService.getName(node.object)
//          if (name !== null) {
//            return name
//          }
//        }
//      }
//      return node.toStringSummary
//    ]);
////    System.out.println(graphAnalysis.toStringSummary()) 
//    System.out.println("# latent variables: " + graphAnalysis.latentVariables.size())
//    System.out.println("# factors: " + graphAnalysis.factorNodes.size())
//  }
  
  // static utils only
  private new () {}  
}