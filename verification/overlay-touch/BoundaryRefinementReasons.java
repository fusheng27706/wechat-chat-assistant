package com.codextemp.wechatcaptureprobe;

/** 对隔离候选的单框前置条件作只读诊断；0可细化，1范围，2外圈，3行孔，4行间断，5无轮廓，6输出范围，7边距。 */
final class BoundaryRefinementReasons {
    static int inspect(ChatLayout.Box coarse, int step, int leftLimit, int topLimit,
                       int rightLimit, int bottomLimit, boolean dark, ChatLayout.Pixels pixels) {
        return inspect(coarse,step,leftLimit,topLimit,rightLimit,bottomLimit,dark,pixels,null);
    }
    /** detail为可选六项计数：左/上/右/下搜索越界、旧框内部/外部的首个行间隙。 */
    static int inspect(ChatLayout.Box coarse, int step, int leftLimit, int topLimit,
                       int rightLimit, int bottomLimit, boolean dark, ChatLayout.Pixels pixels,int[] detail) {
        int radius=step*2;
        int left=coarse.left-radius,top=coarse.top-radius,right=coarse.right+radius,bottom=coarse.bottom+radius;
        if(step<1||left<leftLimit||top<topLimit||right>rightLimit||bottom>bottomLimit){
            if(detail!=null){if(left<leftLimit)detail[0]++;if(top<topLimit)detail[1]++;if(right>rightLimit)detail[2]++;if(bottom>bottomLimit)detail[3]++;}
            return 1;
        }
        int minX=right,minY=bottom,maxX=left-1,maxY=top-1;
        int background=pixels.colorAt(left,top),expected=coarse.outgoing?2:1;
        boolean foundKind=false,endedRows=false;
        int previousLeft=-1,previousRight=-1;
        for(int y=top;y<bottom;y++) {
            int rowLeft=-1,rowRight=-1,gapX=-1;boolean rowGap=false;
            for(int x=left;x<right;x++) {
                int color=pixels.colorAt(x,y);
                if(color==background){if(rowLeft>=0){rowGap=true;if(gapX<0)gapX=x;}continue;}
                if(x==left||y==top||x==right-1||y==bottom-1)return 2;
                if(rowGap){if(detail!=null)detail[gapX>=coarse.left&&gapX<coarse.right&&y>=coarse.top&&y<coarse.bottom?4:5]++;return 3;}
                if(rowLeft<0)rowLeft=x;rowRight=x;
                foundKind|=kind(color,dark)==expected;
                minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);
            }
            if(rowLeft<0){if(previousLeft>=0)endedRows=true;continue;}
            if(endedRows||previousLeft>=0&&(rowLeft>previousRight||rowRight<previousLeft))return 4;
            previousLeft=rowLeft;previousRight=rowRight;
        }
        if(!foundKind||maxX<minX||maxY<minY)return 5;
        int rl=minX-step,rt=minY-step,rr=maxX+1+step,rb=maxY+1+step;
        if(rl>coarse.left||rt>coarse.top||rr<coarse.right||rb<coarse.bottom
                ||rl<leftLimit||rt<topLimit||rr>rightLimit||rb>bottomLimit)return 6;
        for(int y=rt;y<rb;y++)for(int x=rl;x<rr;x++) {
            if(x>=minX&&x<=maxX&&y>=minY&&y<=maxY)continue;
            if(pixels.colorAt(x,y)!=background)return 7;
        }
        return 0;
    }
    private static int kind(int color,boolean dark) {
        int r=(color>>16)&255,g=(color>>8)&255,b=color&255;
        if(g>100&&g>r*1.15&&g>b*1.25)return 2;
        return (dark?r>32&&r<115&&Math.abs(r-g)<10&&Math.abs(r-b)<10:r>247&&g>247&&b>247)?1:0;
    }
}
