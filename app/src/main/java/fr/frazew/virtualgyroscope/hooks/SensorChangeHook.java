package fr.frazew.virtualgyroscope.hooks;

import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import fr.frazew.virtualgyroscope.VirtualSensorListener;

public class SensorChangeHook {
    private static final float NS2S = 1.0f / 1000000000.0f;

    public static List<Object> changeSensorValues(Sensor s, float[] accelerometerValues, float[] magneticValues, Object listener, float[] prevRotationMatrix, long timestamp, long prevTimestamp,
                                                  float[] prevValues, float[][] lastFilterValues, SparseArray<Sensor> sensors) {
        float[] returnValues = new float[3];
        VirtualSensorListener virtualListener = (VirtualSensorListener) listener;

        // Per default, we set the sensor to null so that it doesn't accidentally send the accelerometer's values
        virtualListener.sensorRef = null;

        // We only work when it's an accelerometer's reading. If we were to work on both events, the timeDifference for the gyro would often be 0 resulting in NaN or Infinity
        if (s.getType() == Sensor.TYPE_ACCELEROMETER) {

            if (virtualListener.getSensor().getType() == Sensor.TYPE_GYROSCOPE) {
                float timeDifference = Math.abs((float) (timestamp - prevTimestamp) * NS2S);
                List<Object> valuesList = getGyroscopeValues(accelerometerValues, magneticValues, prevRotationMatrix, timeDifference);
                if (timeDifference != 0.0F) {
                    prevTimestamp = timestamp;
                    prevRotationMatrix = (float[]) valuesList.get(1);
                    float[] values = (float[]) valuesList.get(0);

                    if (Float.isNaN(values[0]) || Float.isInfinite(values[0]))
                        XposedBridge.log("VirtualSensor: Value #" + 0 + " is NaN or Infinity, this should not happen");

                    if (Float.isNaN(values[1]) || Float.isInfinite(values[1]))
                        XposedBridge.log("VirtualSensor: Value #" + 1 + " is NaN or Infinity, this should not happen");

                    if (Float.isNaN(values[2]) || Float.isInfinite(values[2]))
                        XposedBridge.log("VirtualSensor: Value #" + 2 + " is NaN or Infinity, this should not happen");


                    List<Object> filter = filterValues(values, lastFilterValues, prevValues);
                    values = (float[]) filter.get(0);
                    prevValues = (float[]) filter.get(1);
                    lastFilterValues = (float[][]) filter.get(2);

                    System.arraycopy(values, 0, returnValues, 0, values.length);
                    virtualListener.sensorRef = sensors.get(Sensor.TYPE_GYROSCOPE);
                }
            } else if (virtualListener.getSensor().getType() == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR || virtualListener.getSensor().getType() == Sensor.TYPE_ROTATION_VECTOR) {
                float[] values = new float[5];
                float[] rotationMatrix = new float[9];
                SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magneticValues);
                float[] quaternion = rotationMatrixToQuaternion(rotationMatrix);

                values[0] = quaternion[1];
                values[1] = quaternion[2];
                values[2] = quaternion[3];
                values[3] = quaternion[0];
                values[4] = -1;

                System.arraycopy(values, 0, returnValues, 0, returnValues.length);
                if (virtualListener.getSensor().getType() == Sensor.TYPE_ROTATION_VECTOR)
                    virtualListener.sensorRef = sensors.get(Sensor.TYPE_ROTATION_VECTOR);
                else
                    virtualListener.sensorRef = sensors.get(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
            } else if (virtualListener.getSensor().getType() == Sensor.TYPE_GRAVITY) {
                float[] values = new float[3];
                float[] rotationMatrix = new float[9];
                float[] gravity = new float[]{0F, 0F, 9.81F};

                SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magneticValues);
                float[] gravityRot = new float[3];
                gravityRot[0] = gravity[0] * rotationMatrix[0] + gravity[1] * rotationMatrix[3] + gravity[2] * rotationMatrix[6];
                gravityRot[1] = gravity[0] * rotationMatrix[1] + gravity[1] * rotationMatrix[4] + gravity[2] * rotationMatrix[7];
                gravityRot[2] = gravity[0] * rotationMatrix[2] + gravity[1] * rotationMatrix[5] + gravity[2] * rotationMatrix[8];

                values[0] = gravityRot[0];
                values[1] = gravityRot[1];
                values[2] = gravityRot[2];

                System.arraycopy(values, 0, returnValues, 0, values.length);
                virtualListener.sensorRef = sensors.get(Sensor.TYPE_GRAVITY);
            } else if (virtualListener.getSensor().getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
                float[] values = new float[3];
                float[] rotationMatrix = new float[9];
                float[] gravity = new float[]{0F, 0F, 9.81F};

                SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magneticValues);

                float[] gravityRot = new float[3];
                gravityRot[0] = gravity[0] * rotationMatrix[0] + gravity[1] * rotationMatrix[3] + gravity[2] * rotationMatrix[6];
                gravityRot[1] = gravity[0] * rotationMatrix[1] + gravity[1] * rotationMatrix[4] + gravity[2] * rotationMatrix[7];
                gravityRot[2] = gravity[0] * rotationMatrix[2] + gravity[1] * rotationMatrix[5] + gravity[2] * rotationMatrix[8];

                values[0] = accelerometerValues[0] - gravityRot[0];
                values[1] = accelerometerValues[1] - gravityRot[1];
                values[2] = accelerometerValues[2] - gravityRot[2];

                System.arraycopy(values, 0, returnValues, 0, values.length);
                virtualListener.sensorRef = sensors.get(Sensor.TYPE_LINEAR_ACCELERATION);
            }
        }

        List<Object> list = new ArrayList<>();
        list.add(returnValues);
        list.add(prevTimestamp);
        list.add(prevRotationMatrix);
        list.add(prevValues);
        list.add(lastFilterValues);
        return list;
    }

    @SuppressWarnings("unchecked")
    public static class API1617 extends XC_MethodHook {
        // Noise reduction
        float lastFilterValues[][] = new float[3][10];
        float prevValues[] = new float[3];

        //Sensor values
        float[] magneticValues = new float[3];
        float[] accelerometerValues = new float[3];

        //Keeping track of the previous rotation matrix and timestamp
        float[] prevRotationMatrix = new float[9];
        long prevTimestamp = 0;

        private XC_LoadPackage.LoadPackageParam lpparam;

        public API1617(XC_LoadPackage.LoadPackageParam lpparam) {
            this.lpparam = lpparam;
        }

        @Override
        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
            super.beforeHookedMethod(param);
            SparseArray<Sensor> sensors = (SparseArray<Sensor>) XposedHelpers.getStaticObjectField(XposedHelpers.findClass("android.hardware.SystemSensorManager", lpparam.classLoader), "mHandleToSensor");

            Object listener = XposedHelpers.getObjectField(param.thisObject, "mSensorEventListener");
            if (listener instanceof VirtualSensorListener) { // This is our custom listener type, we can start working on the values
                Sensor s = (Sensor)param.args[0];

                //All calculations need data from these two sensors, we can safely get their value every time
                if (s.getType() == Sensor.TYPE_ACCELEROMETER) {
                    this.accelerometerValues = ((float[]) (param.args[1])).clone();
                }
                if (s.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                    this.magneticValues = ((float[]) (param.args[1])).clone();
                }

                List<Object> list = changeSensorValues(s, this.accelerometerValues, this.magneticValues, listener, this.prevRotationMatrix, ((long[]) param.args[2])[0], this.prevTimestamp, this.prevValues, this.lastFilterValues, sensors);
                System.arraycopy((float[])list.get(0), 0, (float[])param.args[1], 0, ((float[])list.get(0)).length);
                this.prevTimestamp = (long)list.get(1);
                this.prevRotationMatrix = (float[])list.get(2);
                this.prevValues = (float[])list.get(3);
                this.lastFilterValues = (float[][])list.get(4);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static class API18Plus extends XC_MethodHook {
        // Noise reduction
        float lastFilterValues[][] = new float[3][10];
        float prevValues[] = new float[3];

        //Sensor values
        float[] magneticValues = new float[3];
        float[] accelerometerValues = new float[3];

        //Keeping track of the previous rotation matrix and timestamp
        float[] prevRotationMatrix = new float[9];
        long prevTimestamp = 0;

        private XC_LoadPackage.LoadPackageParam lpparam;

        public API18Plus(XC_LoadPackage.LoadPackageParam lpparam) {
            this.lpparam = lpparam;
        }

        @Override
        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
            super.beforeHookedMethod(param);

            Object listener = XposedHelpers.getObjectField(param.thisObject, "mListener");
            if (listener instanceof VirtualSensorListener) { // This is our custom listener type, we can start working on the values
                int handle = (int) param.args[0];
                Object mgr = XposedHelpers.getObjectField(param.thisObject, "mManager");
                SparseArray<Sensor> sensors = (SparseArray<Sensor>) XposedHelpers.getObjectField(mgr, "mHandleToSensor");
                Sensor s = sensors.get(handle);

                //All calculations need data from these two sensors, we can safely get their value every time
                if (s.getType() == Sensor.TYPE_ACCELEROMETER) {
                    this.accelerometerValues = ((float[]) (param.args[1])).clone();
                }
                if (s.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                    this.magneticValues = ((float[]) (param.args[1])).clone();
                }

                List<Object> list = changeSensorValues(s, this.accelerometerValues, this.magneticValues, listener, this.prevRotationMatrix, (long) param.args[3], this.prevTimestamp, this.prevValues, this.lastFilterValues, sensors);
                System.arraycopy((float[])list.get(0), 0, (float[])param.args[1], 0, ((float[])list.get(0)).length);
                this.prevTimestamp = (long)list.get(1);
                this.prevRotationMatrix = (float[])list.get(2);
                this.prevValues = (float[])list.get(3);
                this.lastFilterValues = (float[][])list.get(4);
            }
        }
    }


    /*
        Helper functions
     */

    private static List<Object> getGyroscopeValues(float[] currentAccelerometer, float[] currentMagnetic, float[] prevRotationMatrix, float timeDifference) {
        float[] angularRates = new float[] {0.0F, 0.0F, 0.0F};

        float[] currentRotationMatrix = new float[9];
        SensorManager.getRotationMatrix(currentRotationMatrix, null, currentAccelerometer, currentMagnetic);

        SensorManager.getAngleChange(angularRates, currentRotationMatrix, prevRotationMatrix);
        angularRates[0] = -(angularRates[1]*2) / timeDifference;
        angularRates[1] = (angularRates[2]) / timeDifference;
        angularRates[2] = ((angularRates[0]/2) / timeDifference)*0.0F; //Right now this returns weird values, need to look into it @TODO

        List<Object> returnList = new ArrayList<>();
        returnList.add(angularRates);
        returnList.add(currentRotationMatrix);
        return returnList;
    }

    private static List<Object> filterValues(float[] values, float[][] lastFilterValues, float[] prevValues) {
        if (Float.isInfinite(values[0]) || Float.isNaN(values[0])) values[0] = prevValues[0];
        if (Float.isInfinite(values[1]) || Float.isNaN(values[1])) values[1] = prevValues[1];
        if (Float.isInfinite(values[2]) || Float.isNaN(values[2])) values[2] = prevValues[2];

        float[][] newLastFilterValues = new float[3][10];
        for (int i = 0; i < 3; i++) {
            // Apply lowpass on the value
            float alpha = 0.1F;
            float newValue = lowPass(alpha, values[i], prevValues[i]);
            //float newValue = values[i];

            for (int j = 0; j < 10; j++) {
                if (j == 0) continue;
                newLastFilterValues[i][j-1] = lastFilterValues[i][j];
            }
            newLastFilterValues[i][9] = newValue;

            float sum = 0F;
            for (int j = 0; j < 10; j++) {
                sum += lastFilterValues[i][j];
            }
            newValue = sum/10;

            //The gyroscope is moving even after lowpass
            if (newValue != 0.0F) {
                //We are under the declared resolution of the gyroscope, so the value should be 0
                if (Math.abs(newValue) < 0.01F) newValue = 0.0F;
            }

            prevValues[i] = values[i];
            values[i] = newValue;
        }

        List<Object> returnValue = new ArrayList<>();
        returnValue.add(values);
        returnValue.add(prevValues);
        returnValue.add(newLastFilterValues);
        return returnValue;
    }

    private static float lowPass(float alpha, float value, float prev) {
        return prev + alpha * (value - prev);
    }

    /*
        This uses the Hamilton product to multiply the vector converted to a quaternion with the rotation quaternion.
        Returns a new quaternion which is the rotated vector.
        Source:  https://en.wikipedia.org/wiki/Quaternion#Hamilton_product
        -- Not used, but keeping it just in case
     */
    public static float[] rotateVectorByQuaternion(float[] vector, float[] quaternion) {
        float a = vector[0];
        float b = vector[1];
        float c = vector[2];
        float d = vector[3];

        float A = quaternion[0];
        float B = quaternion[1];
        float C = quaternion[2];
        float D = quaternion[3];

        float newQuaternionReal = a*A - b*B - c*C - d*D;
        float newQuaternioni = a*B + b*A + c*D - d*C;
        float newQuaternionj = a*C - b*D + c*A + d*B;
        float newQuaternionk = a*D + b*C - c*B + d*A;

        return new float[] {newQuaternionReal, newQuaternioni, newQuaternionj, newQuaternionk};
    }

    /*
        Credit for this code goes to http://www.euclideanspace.com/maths/geometry/rotations/conversions/matrixToQuaternion/
        Additional credit goes to https://en.wikipedia.org/wiki/Quaternion for helping me understand how quaternions work
     */
    private static float[] rotationMatrixToQuaternion(float[] rotationMatrix) {
        float m00 = rotationMatrix[0];
        float m01 = rotationMatrix[1];
        float m02 = rotationMatrix[2];
        float m10 = rotationMatrix[3];
        float m11 = rotationMatrix[4];
        float m12 = rotationMatrix[5];
        float m20 = rotationMatrix[6];
        float m21 = rotationMatrix[7];
        float m22 = rotationMatrix[8];

        float tr = m00 + m11 + m22;

        float qw;
        float qx;
        float qy;
        float qz;
        if (tr > 0) {
            float S = (float)Math.sqrt(tr+1.0) * 2;
            qw = 0.25F * S;
            qx = (m21 - m12) / S;
            qy = (m02 - m20) / S;
            qz = (m10 - m01) / S;
        } else if ((m00 > m11)&(m00 > m22)) {
            float S = (float)Math.sqrt(1.0 + m00 - m11 - m22) * 2;
            qw = (m21 - m12) / S;
            qx = 0.25F * S;
            qy = (m01 + m10) / S;
            qz = (m02 + m20) / S;
        } else if (m11 > m22) {
            float S = (float)Math.sqrt(1.0 + m11 - m00 - m22) * 2;
            qw = (m02 - m20) / S;
            qx = (m01 + m10) / S;
            qy = 0.25F * S;
            qz = (m12 + m21) / S;
        } else {
            float S = (float)Math.sqrt(1.0 + m22 - m00 - m11) * 2;
            qw = (m10 - m01) / S;
            qx = (m02 + m20) / S;
            qy = (m12 + m21) / S;
            qz = 0.25F * S;
        }
        return new float[] {qw, qx, qy, qz};
    }
}
