package blang.mcmc;

import java.util.Random;

import blang.types.IntImplementation;



public class IntNaiveMHSampler extends MHSampler<IntImplementation>
{
  @Override
  public void propose(Random random, Callback callback)
  {
    final int oldValue = variable.intValue();
    callback.setProposalLogRatio(0.0);
    variable.set(oldValue + (random.nextBoolean() ? 1 : -1));
    if (!callback.sampleAcceptance())
      variable.set(oldValue);
  }
}