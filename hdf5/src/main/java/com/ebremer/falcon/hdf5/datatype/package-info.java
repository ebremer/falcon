/**
 * The public model of HDF5 datatypes: {@link com.ebremer.falcon.hdf5.datatype.Datatype}, a sealed
 * hierarchy with one record per datatype class (fixed-point, floating-point, string, compound, array,
 * enumerated, variable-length, reference, opaque, time, bit field, and complex), plus
 * {@link com.ebremer.falcon.hdf5.datatype.DatatypeClass}.
 *
 * <p>This package is exported. The parser that produces these values from an object-header message
 * lives in the internal {@code message} package.
 */
package com.ebremer.falcon.hdf5.datatype;
