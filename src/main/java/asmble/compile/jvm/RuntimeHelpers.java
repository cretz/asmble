package asmble.compile.jvm;

import java.lang.invoke.*;

class RuntimeHelpers {

    /** Gets a call site that accepts params THEN this THEN index of the table */
    static CallSite bootstrapIndirect(MethodHandles.Lookup caller, String name, MethodType type) throws Exception {
        MethodType withoutIndexOrThis = type.dropParameterTypes(type.parameterCount() - 2, type.parameterCount());
        // Handle with mh at the beginning
        MethodHandle mhFirst = MethodHandles.exactInvoker(withoutIndexOrThis);
        // Last index first, then move each up 1
        int[] reorder = new int[mhFirst.type().parameterCount()];
        for (int i = 0; i < reorder.length; i++) {
            if (i == 0) reorder[0] = reorder.length - 1;
            else reorder[i] = i - 1;
        }
        // Method handle that moves the method handle to the end
        MethodHandle mhAtEnd = MethodHandles.permuteArguments(mhFirst,
                mhFirst.type().dropParameterTypes(0, 1).appendParameterTypes(MethodHandle.class), reorder );
        // Method handle that changes an ending table + index to a method handle using the table
        MethodHandle tableAndIndexAtEnd = MethodHandles.collectArguments(mhAtEnd, mhAtEnd.type().parameterCount() - 1,
                MethodHandles.arrayElementGetter(MethodHandle[].class));
        // Method handle that changes second-to-last this to table
        MethodHandle thisAndIndexAtEnd = MethodHandles.filterArguments(tableAndIndexAtEnd,
                tableAndIndexAtEnd.type().parameterCount() - 2,
                caller.findGetter(caller.lookupClass(), "table", MethodHandle[].class));
        return new ConstantCallSite(thisAndIndexAtEnd);
    }
}
