package com.codextemp.wechatcaptureprobe;

import java.util.*;

public final class CacheTokenSnapshotRegression {
    private static final class Entry {
        final int[] storage;
        final int length;
        final boolean encoded=false;
        Entry(int[] pixels){storage=pixels;length=pixels.length;}
    }
    private static int checks;
    private static void require(boolean value){checks++;if(!value)throw new AssertionError("token-snapshot-"+checks);}
    public static void main(String[] args) {
        Map<String,Object> a=new LinkedHashMap<>(),b=new LinkedHashMap<>();
        a.put("10:10:false:1:PRIVATE_BODY",new Object());
        a.put("latin-token:10:10:false:1:PRIVATE_OLD",new Entry(new int[100]));
        int[] changed=new int[100];changed[55]=1;
        b.put("latin-token:10:10:false:1:PRIVATE_NEW",new Entry(changed));
        try(CacheTokenSnapshot before=new CacheTokenSnapshot(a);CacheTokenSnapshot after=new CacheTokenSnapshot(b)) {
            changed[55]=2;
            String result=before.compare(after);
            require(result.contains("beforeTokens=1 afterTokens=1"));
            require(result.contains("pixelPairs=1 unmatchedShapes=0 comparedPixels=100 changedPixels=1"));
            require(!result.contains("PRIVATE")&&!result.contains("latin-token:"));
            require(result.contains("beforeShapes=invalid0;10x10r0d1n1"));
            after.close();require(before.compare(after).contains("pixelPairs=0"));
        }
        b.clear();b.put("latin-token:11:10:false:1:PRIVATE_NEW",new Entry(new int[110]));
        try(CacheTokenSnapshot before=new CacheTokenSnapshot(a);CacheTokenSnapshot after=new CacheTokenSnapshot(b)) {
            require(before.compare(after).contains("unmatchedShapes=1"));
        }
        boolean refused=false;
        for(int i=0;i<33;i++)b.put("body"+i,new Object());
        try(CacheTokenSnapshot ignored=new CacheTokenSnapshot(b)){}catch(IllegalArgumentException expected){refused=true;}
        require(refused);
        try(CacheKeySnapshot before=new CacheKeySnapshot(1,Arrays.asList("10:10:false:1:PRIVATE_BODY", "latin-token:10:10:false:1:PRIVATE_OLD"));
            CacheKeySnapshot after=new CacheKeySnapshot(1,Arrays.asList("10:10:false:1:PRIVATE_BODY", "latin-token:10:10:false:1:PRIVATE_NEW"))) {
            String summary=before.compare(after);
            require(summary.contains("retainedKeys=1 removedKeys=1 sameShapeNewFingerprint=1 newShapeKeys=0"));
            require(!summary.contains("PRIVATE")&&!summary.contains("latin-token"));
        }
        System.out.println("PASS tokenSnapshotChecks="+checks);
    }
}
