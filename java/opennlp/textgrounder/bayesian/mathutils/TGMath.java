///////////////////////////////////////////////////////////////////////////////
//  Copyright 2010 Taesun Moon <tsunmoon@gmail.com>.
// 
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
// 
//       http://www.apache.org/licenses/LICENSE-2.0
// 
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//  under the License.
///////////////////////////////////////////////////////////////////////////////
package opennlp.textgrounder.bayesian.mathutils;

import opennlp.textgrounder.bayesian.ec.util.MersenneTwisterFast;

/**
 *
 * @author Taesun Moon <tsunmoon@gmail.com>
 */
public class TGMath {

    public static double dotProduct(double[] _v1, double[] _v2) {
        double sum = 0;
        try {
            for (int i = 0;; ++i) {
                sum += _v1[i] * _v2[i];
            }
        } catch (ArrayIndexOutOfBoundsException e) {
        }
        return sum;
    }

    public static double l2Norm(double[] _vec) {
        double sum = 0;
        try {
            for (int i = 0;; ++i) {
                sum += Math.pow(_vec[i], 2);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
        }
        return Math.sqrt(sum);
    }

    public static double sphericalDensity(double[] _x, double[] _mu,
          double _kappa) {
        return _kappa * Math.exp(_kappa * dotProduct(_x, _mu)) / (4 * Math.PI * Math.sinh(_kappa));
    }

    public static double sphericalDensity(double _alpha, double _beta,
          double _theta, double _phi, double _kappa) {
        return _kappa * Math.exp(_kappa
              * (Math.cos(_alpha) * Math.cos(_theta)
              + Math.sin(_alpha) * Math.sin(_theta) * Math.cos(_phi - _beta)))
              / (4 * Math.PI * Math.sinh(_kappa));
    }

    public static double proportionalSphericalDensity(double[] _x, double[] _mu,
          double _kappa) {
        return Math.exp(_kappa * dotProduct(_x, _mu));
    }

    public static double proportionalSphericalDensity(double _alpha,
          double _beta,
          double _theta, double _phi, double _kappa) {
        return Math.exp(_kappa
              * (Math.cos(_alpha) * Math.cos(_theta)
              + Math.sin(_alpha) * Math.sin(_theta) * Math.cos(_phi - _beta)));
    }

    public static double[] sphericalToCartesian(double _lat, double _long) {
        double[] cart = new double[3];
        double theta = latToRadians(_lat);
        double phi = longToRadians(_long);
        cart[0] = Math.cos(theta);
        cart[1] = Math.sin(theta) * Math.cos(phi);
        cart[2] = Math.sin(theta) * Math.sin(phi);
        return cart;
    }

    public static double[] cartesianToSpherical(double[] _x) {
        double[] spher = new double[2];
        spher[0] = Math.acos(_x[2]);
        spher[1] = Math.atan2(_x[1], _x[0]);
        return spher;
    }

    public static double latToRadians(double _lat) {
        return (_lat / 180 + 0.5) * Math.PI;
    }

    public static double longToRadians(double _long) {
        return (_long / 180 + 1) * Math.PI;
    }

    public static double[] sampleFromFisher(MersenneTwisterFast _mtf, int _N) {
        double[] samples = new double[_N];
        try {
            for (int i = 0;; ++i) {
                samples[i] = _mtf.nextDouble();
            }
        } catch (ArrayIndexOutOfBoundsException e) {
        }
        return samples;
    }
}
