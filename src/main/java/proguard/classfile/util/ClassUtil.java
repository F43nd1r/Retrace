/*
 * ProGuard -- shrinking, optimization, obfuscation, and preverification
 *             of Java bytecode.
 *
 * Copyright (c) 2002-2014 Eric Lafortune (eric@graphics.cornell.edu)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package proguard.classfile.util;

/**
 * Utility methods for converting between internal and external representations of names and descriptions.
 *
 * @author Eric Lafortune, modified by F43nd1r
 */
public class ClassUtil {


    private static final char JAVA_PACKAGE_SEPARATOR = '.';
    private static final char CLASS_PACKAGE_SEPARATOR = '/';

    /**
     * Converts an external class name into an internal class name.
     *
     * @param externalClassName the external class name e.g. "<code>java.lang.Object</code>"
     * @return the internal class name, e.g. "<code>java/lang/Object</code>".
     */
    public static String internalClassName(String externalClassName) {
        return externalClassName.replace(JAVA_PACKAGE_SEPARATOR, CLASS_PACKAGE_SEPARATOR);
    }


    /**
     * Converts an internal class name into an external class name.
     *
     * @param internalClassName the internal class name, e.g. "<code>java/lang/Object</code>".
     * @return the external class name, e.g. "<code>java.lang.Object</code>".
     */
    public static String externalClassName(String internalClassName) {
        return internalClassName.replace(CLASS_PACKAGE_SEPARATOR, JAVA_PACKAGE_SEPARATOR);
    }


}
