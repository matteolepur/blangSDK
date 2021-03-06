package blang.io

import java.util.Optional
import blang.inits.Input
import blang.inits.DesignatedConstructor
import blang.inits.GlobalArg
import blang.io.internals.GlobalDataSourceStore

/**
 * A DataSource made available as a default to all Plate and Plated declared afterwards.
 */
class GlobalDataSource extends DataSource { 
  @DesignatedConstructor
  new(
    @Input(formatDescription = "Path to the DataSource.") Optional<String> path, 
    @GlobalArg GlobalDataSourceStore store
  ) {
    super(path)
    store.set(this)
  }
  
  private new(Optional<String> path) {
    super(path)
  }
  
  def static GlobalDataSource empty() {
    return new GlobalDataSource(Optional.empty)
  }
}