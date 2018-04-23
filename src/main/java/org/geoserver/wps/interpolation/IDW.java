package org.geoserver.wps.interpolation;

import java.util.ArrayList;
import java.util.List;

import org.geoserver.wps.gs.GeoServerProcess;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.data.Query;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.factory.Hints;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.filter.text.cql2.CQL;
import org.geotools.filter.text.cql2.CQLException;
import org.geotools.geometry.jts.JTS;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.process.ProcessException;
import org.geotools.process.factory.DescribeParameter;
import org.geotools.process.factory.DescribeProcess;
import org.geotools.process.factory.DescribeResult;
import org.geotools.process.spatialstatistics.gridcoverage.RasterInterpolationIDWOperation;
import org.geotools.process.spatialstatistics.gridcoverage.RasterRadius;
import org.opengis.coverage.grid.GridGeometry;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.Filter;
import org.opengis.geometry.MismatchedDimensionException;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;
import org.opengis.util.ProgressListener;
import org.geotools.process.spatialstatistics.gridcoverage.RasterInterpolationOperator.RadiusType;
import org.geotools.process.spatialstatistics.util.BBOXExpandingFilterVisitor;
import org.geotools.referencing.CRS;

import com.vividsolutions.jts.geom.Geometry;

@DescribeProcess(title = "idw", description = "IDW Interpolation")
public class IDW implements GeoServerProcess {
  
  @DescribeResult(name = "result", description = "Output raster")
  public GridCoverage2D execute(
    @DescribeParameter(name = "data", description = "Input features") SimpleFeatureCollection inputFeatures, 
    @DescribeParameter(name = "valueAttr", description = "Name of attribute containing the data value to be interpolated") String valueAttr, 
    @DescribeParameter(name = "power", description = "The exponent(default 2.0) of distance.", min = 0, max = 1, defaultValue = "2.0") Double power, 
    @DescribeParameter(name = "radiusType", description = "The search radius type", min = 0, max = 1, defaultValue = "Variable") RadiusType radiusType, 
    @DescribeParameter(name = "numberOfPoints", description = "The numberOfPoints is an integer value specifying the number of nearest input sample points to be used to perform the interpolation.", min = 0, max = 1, defaultValue = "12") Integer numberOfPoints, 
    @DescribeParameter(name = "distance", description = "The distance specifies the distance, in map units, by which to limit the search for the nearest input sample points.", min = 0, max = 1, defaultValue = "0.0") Double distance, 
    @DescribeParameter(name = "cellSize", description = "The cell size for the output gridcoverage.", min = 0, max = 1, defaultValue = "0.0") Double cellSize,
    // output image parameters
    @DescribeParameter(name = "outputBBOX", description = "Bounding box for output") ReferencedEnvelope outputEnv, 
    @DescribeParameter(name = "outputWidth", description = "Width of the output raster in pixels", min = 0, max = 1) Integer outputWidth, 
    @DescribeParameter(name = "outputHeight", description = "Height of the output raster in pixels", min = 0, max = 1) Integer outputHeight,
    
    ProgressListener monitor) throws ProcessException {
    
    /**
     * --------------------------------------------- Check that process arguments are valid ---------------------------------------------
     */
    if (valueAttr == null || valueAttr.length() <= 0) {
      throw new IllegalArgumentException("Value attribute must be specified");
    }
    
    /**
     * Compute transform to convert input coords into output CRS
     */
    CoordinateReferenceSystem srcCRS = inputFeatures.getSchema().getCoordinateReferenceSystem();
    CoordinateReferenceSystem dstCRS = outputEnv.getCoordinateReferenceSystem();
    MathTransform trans = null;
    try {
        trans = CRS.findMathTransform(srcCRS, dstCRS);
    } catch (FactoryException e) {
        throw new ProcessException(e);
    }
    
    try {
      inputFeatures = transformFeatures(inputFeatures, trans);
    } catch (Exception e) {
      throw new ProcessException(e);
    }
    
    /*
    CoordinateReferenceSystem trsCRS = inputFeatures.getSchema().getCoordinateReferenceSystem();
    System.out.println("----=============================================");
    System.out.println(trsCRS);
    */
    if (cellSize == 0.0) {
      cellSize = Math.min(outputEnv.getWidth(), outputEnv.getHeight()) / 250.0;
    }
    
    power = power == 0 ? 2.0 : power;
    
    RasterRadius rasterRadius = new RasterRadius();
    if (radiusType == RadiusType.Variable) {
        numberOfPoints = numberOfPoints == 0 ? 12 : numberOfPoints;
        if (distance > 0)
            rasterRadius.setVariable(numberOfPoints, distance);
        else
            rasterRadius.setVariable(numberOfPoints);
    } else {
        // The default radius is five times the cell size of the output raster.
        distance = distance == 0 ? cellSize * 5 : distance;
        if (numberOfPoints > 0)
            rasterRadius.setFixed(distance, numberOfPoints);
        else
            rasterRadius.setFixed(distance);
    }
    
    GridCoverage2D resultGc = null;
    RasterInterpolationIDWOperation process = new RasterInterpolationIDWOperation();
    process.getRasterEnvironment().setExtent(outputEnv);

    if (cellSize > 0) {
        double origCellSize = process.getRasterEnvironment().getCellSize();
        process.getRasterEnvironment().setCellSize(cellSize);
        resultGc = process.execute(inputFeatures, valueAttr, power, rasterRadius);
        process.getRasterEnvironment().setCellSize(origCellSize);
    } else {
        resultGc = process.execute(inputFeatures, valueAttr, power, rasterRadius);
    }
    
    return resultGc;
  }
  
  private SimpleFeatureCollection transformFeatures(SimpleFeatureCollection inputFeatures, MathTransform trans) throws MismatchedDimensionException, TransformException {
    SimpleFeatureType featureType = inputFeatures.getSchema();
    List<SimpleFeature> features = new ArrayList<SimpleFeature>();
    
    SimpleFeatureIterator iterator = inputFeatures.features();
    while (iterator.hasNext()) {
      SimpleFeature srcFeature = iterator.next();
      SimpleFeature dstFeature = SimpleFeatureBuilder.copy(srcFeature);
      dstFeature.setAttributes(srcFeature.getAttributes());
      Geometry srcGeometry = (Geometry)srcFeature.getDefaultGeometry();
      Geometry dstGeometry = JTS.transform(srcGeometry, trans);
      dstFeature.setDefaultGeometry(dstGeometry);
      features.add(dstFeature);
    }
    iterator.close();
    
    SimpleFeatureCollection collection = new ListFeatureCollection(featureType, features);
    return collection;
  }
  
  @SuppressWarnings("unused")
  private Filter expandBBox(Filter filter, double distance) {
    return (Filter) filter.accept(new BBOXExpandingFilterVisitor(distance, distance, distance, distance), null);
  }
  
  /**
   * Given a target query and a target grid geometry returns the query to be used to read the input data of the process involved in rendering. In this process this method is used to:
   * <ul>
   * <li>determine the extent & CRS of the output grid
   * <li>expand the query envelope to ensure stable surface generation
   * <li>modify the query hints to ensure point features are returned
   * </ul>
   * Note that in order to pass validation, all parameters named here must also appear in the parameter list of the <tt>execute</tt> method, even if they are not used there.
   * 
   * @param targetQuery
   *          the query used against the data source
   * @param targetGridGeometry
   *          the grid geometry of the destination image
   * @return The transformed query
   */
  public Query invertQuery(
    @DescribeParameter(name = "valueAttr", description = "Name of attribute containing the data value to be interpolated") String valueAttr, 
    Query targetQuery, GridGeometry targetGridGeometry) throws ProcessException {
    
    // default is no expansion
    //double distance = 1000;
    
    //targetQuery.setFilter(expandBBox(targetQuery.getFilter(), distance));
    
    try {
      targetQuery.setFilter(CQL.toFilter(valueAttr+" > -9999.0"));
      //targetQuery.setFilter(expandBBox(targetQuery.getFilter(), distance));
    } catch (CQLException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    
    // clear properties to force all attributes to be read
    // (required because the SLD processor cannot see the value attribute specified in the transformation)
    // TODO: set the properties to read only the specified value attribute
    targetQuery.setProperties(null);
    
    // set the decimation hint to ensure points are read
    Hints hints = targetQuery.getHints();
    hints.put(Hints.GEOMETRY_DISTANCE, 0.0);
    
    return targetQuery;
  }
  
}
