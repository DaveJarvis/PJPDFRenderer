/*
 * $Id: PDFColorSpace.java,v 1.5 2009/03/08 20:46:16 tomoke Exp $
 *
 * Copyright 2004 Sun Microsystems, Inc., 4150 Network Circle,
 * Santa Clara, California 95054, U.S.A. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package com.sun.pdfview.colorspace;

import com.sun.pdfview.PDFObject;
import com.sun.pdfview.PDFPaint;
import com.sun.pdfview.PDFParseException;
import com.sun.pdfview.function.PDFFunction;

import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

import static com.sun.pdfview.colorspace.PatternSpace.PATTERN_SPACE;
import static java.awt.color.ColorSpace.CS_sRGB;
import static java.awt.color.ColorSpace.getInstance;


/**
 * A color space that can convert a set of color components into
 * PDFPaint.
 *
 * @author Mike Wessler
 */
public class PDFColorSpace {
  /**
   * the name of the device-dependent gray color space
   */
  public static final int COLORSPACE_GRAY = 0;

  /**
   * the name of the device-dependent RGB color space
   */
  public static final int COLORSPACE_RGB = 1;

  /**
   * the name of the device-dependent CMYK color space
   */
  public static final int COLORSPACE_CMYK = 2;

  /**
   * the name of the pattern color space
   */
  public static final int COLORSPACE_PATTERN = 3;

  /**
   * the device-dependent color spaces
   */
  private static final PDFColorSpace RGB_SPACE =
    new PDFColorSpace( getInstance( CS_sRGB ) );
  private static final PDFColorSpace CMYK_SPACE =
    new PDFColorSpace( new CMYKColorSpace() );

  /**
   * graySpace and the gamma correction for it.
   */
  private static final PDFColorSpace GRAY_SPACE;

  static {
    try {
      GRAY_SPACE = new PDFColorSpace(
        new ICC_ColorSpace( ICC_Profile.getInstance(
          PDFColorSpace.class.getResourceAsStream( "sGray.icc" ) ) ) );
    } catch( Exception e ) {
      throw new RuntimeException( e );
    }
  }

  /**
   * the color space
   */
  ColorSpace cs;

  /**
   * create a PDFColorSpace based on a Java ColorSpace
   *
   * @param cs the Java ColorSpace
   */
  protected PDFColorSpace( ColorSpace cs ) {
    this.cs = cs;
  }

  /**
   * Get a color space by name
   *
   * @param name the name of one of the device-dependent color spaces
   */
  public static PDFColorSpace getColorSpace( int name ) {
    return switch( name ) {
      case COLORSPACE_GRAY -> GRAY_SPACE;
      case COLORSPACE_RGB -> RGB_SPACE;
      case COLORSPACE_CMYK -> CMYK_SPACE;
      case COLORSPACE_PATTERN -> PATTERN_SPACE;
      default -> throw new IllegalArgumentException(
        "Unknown Color Space name: " + name );
    };
  }

  /**
   * Get a color space specified in a PDFObject
   *
   * @param csobj the PDFObject with the colorspace information
   */
  public static PDFColorSpace getColorSpace(
    PDFObject csobj, Map<String, PDFObject> resources )
    throws IOException {
    String name;

    PDFObject colorSpaces = null;

    if( resources != null ) {
      colorSpaces = resources.get( "ColorSpace" );
    }

    if( csobj.getType() == PDFObject.NAME ) {
      name = csobj.getStringValue();

      if( name.equals( "DeviceGray" ) || name.equals( "G" ) ) {
        return getColorSpace( COLORSPACE_GRAY );
      }
      else if( name.equals( "DeviceRGB" ) || name.equals( "RGB" ) ) {
        return getColorSpace( COLORSPACE_RGB );
      }
      else if( name.equals( "DeviceCMYK" ) || name.equals( "CMYK" ) ) {
        return getColorSpace( COLORSPACE_CMYK );
      }
      else if( name.equals( "Pattern" ) ) {
        return getColorSpace( COLORSPACE_PATTERN );
      }
      else if( colorSpaces != null ) {
        csobj = colorSpaces.getDictRef( name );
      }
    }

    if( csobj == null ) {
      return null;
    }
    else if( csobj.getCache() != null ) {
      return (PDFColorSpace) csobj.getCache();
    }

    PDFColorSpace value = null;

    // csobj is [/name <<dict>>]
    PDFObject[] ary = csobj.getArray();
    name = ary[ 0 ].getStringValue();

    /*
     * 4.5.5 [/Indexed baseColor hival lookup]
     */
    // number of indices= ary[2], data is in ary[3];
    switch( name ) {
      case "CalGray" -> value =
        new PDFColorSpace( new CalGrayColor( ary[ 1 ] ) );
      case "CalRGB" -> value = new PDFColorSpace( new CalRGBColor( ary[ 1 ] ) );
      case "Lab" -> value = new PDFColorSpace( new LabColor( ary[ 1 ] ) );
      case "ICCBased" -> {
        ByteArrayInputStream bais =
          new ByteArrayInputStream( ary[ 1 ].getStream() );
        ICC_Profile profile = ICC_Profile.getInstance( bais );
        value = new PDFColorSpace( new ICC_ColorSpace( profile ) );
      }
      case "Separation", "DeviceN" -> {
        PDFColorSpace alternate = getColorSpace( ary[ 2 ], resources );
        PDFFunction function = PDFFunction.getFunction( ary[ 3 ] );
        value = new AlternateColorSpace( alternate, function );
      }
      case "Indexed", "I" -> {
        PDFColorSpace refspace = getColorSpace( ary[ 1 ], resources );
        int count = ary[ 2 ].getIntValue();
        if( refspace != null ) {
          value = new IndexedColor( refspace, count, ary[ 3 ] );
        }
      }
      case "Pattern" -> {
        if( ary.length == 1 ) {
          return getColorSpace( COLORSPACE_PATTERN );
        }
        PDFColorSpace base = getColorSpace( ary[ 1 ], resources );
        return new PatternSpace( base );
      }
      default -> throw new PDFParseException( "Unknown color space: " + name +
                                                " with " + ary[ 1 ] );
    }

    csobj.setCache( value );

    return value;
  }

  /**
   * get the number of components expected in the getPaint command
   */
  public int getNumComponents() {
    return cs.getNumComponents();
  }

  /**
   * get the PDFPaint representing the color described by the
   * given color components
   *
   * @param components the color components corresponding to the given
   *                   colorspace
   * @return a PDFPaint object representing the closest Color to the
   * given components.
   */
  public PDFPaint getPaint( float[] components ) {
    float[] rgb = cs.toRGB( components );

    return PDFPaint.getColorPaint( new Color( rgb[ 0 ], rgb[ 1 ], rgb[ 2 ] ) );
  }

  /**
   * get the original Java ColorSpace.
   */
  public ColorSpace getColorSpace() {
    return cs;
  }
}
