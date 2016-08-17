package blang.types

import blang.inits.Implementation
import blang.mcmc.Samplers
import blang.mcmc.RealNaiveMHSampler
import blang.inits.DesignatedConstructor
import java.util.List
import blang.inits.Input
import com.google.common.base.Joiner
import blang.inits.ConstructorArg
import blang.runtime.InitContext

@Implementation(RealImpl)
@FunctionalInterface
interface Real { 
  
  def double doubleValue()
  
  @Samplers(RealNaiveMHSampler)
  static class RealImpl implements Real {
    
    var double value
    
    @DesignatedConstructor
    new(
      @Input(formatDescription = "A real number") List<String> input,
      @ConstructorArg(InitContext::KEY) InitContext initContext
    ) {
      val String strValue = Joiner.on(" ").join(input).trim
      this.value =
        if (strValue == NA::SYMBOL) {
          initContext.markAsObserved(this, false)
          0.0
        } else {
          Double.parseDouble(strValue)
        }
    }
    
    override double doubleValue() {
      return value
    }
    
    def void set(double newValue) {
      this.value = newValue
    }
  }
}