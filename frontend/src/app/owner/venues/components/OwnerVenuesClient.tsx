'use client'

import { useEffect, useState, useCallback, useTransition } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Plus,
  MoreVertical,
  Edit,
  Pause,
  PlayCircle,
  Trash,
  Loader2,
  Package,
  CalendarDays,
  ChevronDown,
  ChevronRight,
  Sliders,
  Building2,
  Trophy,
  Wrench,
} from "lucide-react";
import { stadiumService } from "@/lib/services/stadium";
import { StadiumResponse, ComplexResponse } from "@/types/stadium";
import { toast } from "sonner";
import { AccessoryManagerDialog } from "@/components/venues/AccessoryManagerDialog";
import { ConfirmDialog } from "@/components/common/ConfirmDialog";
import { useConfirm } from "@/hooks/useConfirm";
import { MaintenanceScheduleDialog } from "@/components/venues/MaintenanceScheduleDialog";

// Quick Dialogs
import { QuickCreateFacilityDialog } from './QuickCreateFacilityDialog'
import { QuickCreateCourtDialog } from './QuickCreateCourtDialog'
import { BulkTimeSlotConfigDialog } from './BulkSlotConfigDialog'
import { EditComplexDialog } from './EditComplexDialog'
import { EditFacilityDialog } from './EditFacilityDialog'

interface VenueModalData {
  id: number;
  name: string;
  type?: 'stadium' | 'complex';
}

interface OwnerVenuesClientProps {
  initialComplexes: ComplexResponse[]
  initialVenues: StadiumResponse[]
}

export default function OwnerVenuesClient({
  initialComplexes,
  initialVenues,
}: OwnerVenuesClientProps) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const currentStatus = searchParams.get('status') || '';
  
  const [isPending, startTransition] = useTransition();

  const [complexes, setComplexes] = useState<ComplexResponse[]>(initialComplexes);
  const [venues, setVenues] = useState<StadiumResponse[]>(initialVenues);
  const [loading, setLoading] = useState(false);

  // Sync state when initial props update via SSR query-param change.
  // fetchTreeData is NOT called here — router.push in handleStatusChange already
  // triggers a Server Component re-render which supplies fresh initialComplexes/initialVenues.
  useEffect(() => {
    setComplexes(initialComplexes);
    setVenues(initialVenues);
  }, [initialComplexes, initialVenues]);

  // Handle URL query parameters for auto-expanding 3 levels & scrolling to target venue node
  useEffect(() => {
    const complexIdParam = searchParams.get('complexId');
    const facilityIdParam = searchParams.get('facilityId');
    const stadiumIdParam = searchParams.get('stadiumId');

    if (complexIdParam) {
      setCollapsedComplexes(prev => ({ ...prev, [Number(complexIdParam)]: false }));
    }
    if (facilityIdParam) {
      setCollapsedFacilities(prev => ({ ...prev, [Number(facilityIdParam)]: false }));
    }
    let targetId: string | null = null;
    if (stadiumIdParam && venues.length > 0) {
      const matchVenue = venues.find(v => v.stadiumId === Number(stadiumIdParam));
      if (matchVenue) {
        if (matchVenue.complexId) {
          setCollapsedComplexes(prev => ({ ...prev, [matchVenue.complexId!]: false }));
        }
        if (matchVenue.parentStadiumId) {
          setCollapsedFacilities(prev => ({ ...prev, [matchVenue.parentStadiumId!]: false }));
        }
        if (matchVenue.nodeType === 'FACILITY') {
          targetId = `facility-${stadiumIdParam}`;
        } else if (matchVenue.nodeType === 'COURT') {
          targetId = `court-${stadiumIdParam}`;
        }
      }
    }
    if (!targetId && facilityIdParam) {
      targetId = `facility-${facilityIdParam}`;
    }
    if (!targetId && complexIdParam) {
      targetId = `complex-${complexIdParam}`;
    }
    if (targetId) {
      setTimeout(() => {
        const el = document.getElementById(targetId);
        if (el) {
          el.scrollIntoView({ behavior: 'smooth', block: 'center' });
          el.classList.add('ring-2', 'ring-emerald-500', 'bg-emerald-50/50');
          setTimeout(() => {
            el.classList.remove('ring-2', 'ring-emerald-500', 'bg-emerald-50/50');
          }, 3000);
        }
      }, 400);
    }
  }, [searchParams, venues]);

  // Modal states for old actions
  const [isAccessoryOpen, setIsAccessoryOpen] = useState(false);
  const [selectedVenueForAccessory, setSelectedVenueForAccessory] = useState<VenueModalData | null>(null);
  
  const [suspendVenue, setSuspendVenue] = useState<VenueModalData | null>(null);
  const [activateVenue, setActivateVenue] = useState<VenueModalData | null>(null);
  const [deleteVenue, setDeleteVenue] = useState<VenueModalData | null>(null);
  const [deleteConfirmText, setDeleteConfirmText] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [editingComplex, setEditingComplex] = useState<ComplexResponse | null>(null);
  const [editingFacility, setEditingFacility] = useState<StadiumResponse | null>(null);
  const [maintenanceTarget, setMaintenanceTarget] = useState<{ type: 'stadium' | 'complex'; id: number; name: string } | null>(null);

  // Quick action states
  const [activeComplexIdForFacility, setActiveComplexIdForFacility] = useState<number | null>(null);
  const [activeFacilityForCourt, setActiveFacilityForCourt] = useState<StadiumResponse | null>(null);
  const [bulkFacilityId, setBulkFacilityId] = useState<number | null>(null);
  const [bulkComplexId, setBulkComplexId] = useState<number | null>(null);
  const [bulkCourtsList, setBulkCourtsList] = useState<StadiumResponse[]>([]);

  // Tree collapse state
  const [collapsedComplexes, setCollapsedComplexes] = useState<Record<number, boolean>>({});
  const [collapsedFacilities, setCollapsedFacilities] = useState<Record<number, boolean>>({});

  // Custom confirm hook
  const { 
    isOpen: isConfirmOpen, 
    isLoading: isActionLoading, 
    options: confirmOptions, 
    close: closeConfirm, 
    execute: executeConfirm 
  } = useConfirm()

  const fetchTreeData = useCallback(async (showFullLoader = false) => {
    if (showFullLoader) setLoading(true);
    try {
      const [complexesRes, venuesRes] = await Promise.all([
        stadiumService.getMyComplexes(),
        stadiumService.getMyStadiums(currentStatus ? { status: currentStatus } : undefined)
      ]);
      setComplexes(complexesRes);
      setVenues(venuesRes);
    } catch (error) {
      toast.error("Không thể tải danh sách tổ hợp và sân.");
    } finally {
      if (showFullLoader) setLoading(false);
    }
  }, [currentStatus]);

  const handleStatusChange = (status: string) => {
    startTransition(() => {
      const params = new URLSearchParams(searchParams.toString());
      if (status) {
        params.set('status', status);
      } else {
        params.delete('status');
      }
      router.push(`/owner/venues?${params.toString()}`);
    });
  };

  const toggleComplex = (complexId: number) => {
    setCollapsedComplexes(prev => ({ ...prev, [complexId]: !prev[complexId] }));
  };

  const toggleFacility = (facilityId: number) => {
    setCollapsedFacilities(prev => ({ ...prev, [facilityId]: !prev[facilityId] }));
  };

  const handleSuspend = async () => {
    if (!suspendVenue) return;
    setIsSubmitting(true);
    try {
      if (suspendVenue.type === 'complex') {
        await stadiumService.suspendComplex(suspendVenue.id);
        toast.success("Đã tạm ngưng hoạt động tổ hợp thành công.");
      } else {
        await stadiumService.suspendStadium(suspendVenue.id);
        toast.success("Đã tạm ngưng hoạt động sân thành công.");
      }
      setSuspendVenue(null);
      await fetchTreeData();
    } catch (error) {
      toast.error("Có lỗi xảy ra khi tạm ngưng.");
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleActivate = async () => {
    if (!activateVenue) return;
    setIsSubmitting(true);
    try {
      if (activateVenue.type === 'complex') {
        await stadiumService.activateComplex(activateVenue.id);
        toast.success("Đã mở lại hoạt động tổ hợp thành công.");
      } else {
        await stadiumService.activateStadium(activateVenue.id);
        toast.success("Đã mở lại hoạt động sân thành công.");
      }
      setActivateVenue(null);
      await fetchTreeData();
    } catch (error) {
      toast.error("Có lỗi xảy ra khi mở lại.");
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteVenue) return;
    if (deleteConfirmText !== "DELETE") {
      toast.error("Vui lòng nhập chữ DELETE để xác nhận.");
      return;
    }
    setIsSubmitting(true);
    try {
      await stadiumService.deleteStadium(deleteVenue.id);
      toast.success("Đã xóa sân thành công.");
      fetchTreeData();
      setDeleteVenue(null);
      setDeleteConfirmText("");
    } catch (error) {
      toast.error("Có lỗi xảy ra khi xóa sân.");
    } finally {
      setIsSubmitting(false);
    }
  };

  const getStatusBadge = (status: string) => {
    switch (status) {
      case "AVAILABLE":
        return <Badge className="bg-green-100 text-green-700 hover:bg-green-100">Hoạt động</Badge>;
      case "MAINTENANCE":
        return <Badge className="bg-yellow-100 text-yellow-700 hover:bg-yellow-100">Bảo trì</Badge>;
      case "CLOSED":
        return <Badge className="bg-red-100 text-red-700 hover:bg-red-100">Đóng cửa</Badge>;
      default:
        return <Badge className="bg-gray-100 text-gray-700 hover:bg-gray-100">{status}</Badge>;
    }
  };

  const getApprovedStatusBadge = (approvedStatus: string) => {
    switch (approvedStatus) {
      case "PENDING":
        return <Badge className="bg-amber-100 text-amber-700 border-amber-200 hover:bg-amber-100">Chờ duyệt</Badge>;
      case "APPROVED":
        return <Badge className="bg-emerald-100 text-emerald-700 border-emerald-200 hover:bg-emerald-100">Đã duyệt</Badge>;
      case "REJECTED":
        return <Badge className="bg-rose-100 text-rose-700 border-rose-200 hover:bg-rose-100">Từ chối</Badge>;
      default:
        return <Badge variant="outline">{approvedStatus}</Badge>;
    }
  };

  const renderedComplexes = complexes.filter((complex) => {
    if (!currentStatus) return true;

    let complexMatches = false;
    if (currentStatus === 'PENDING') {
      complexMatches = complex.approvedStatus === 'PENDING';
    } else if (currentStatus === 'SUSPENDED') {
      complexMatches = complex.complexStatus === 'MAINTENANCE';
    } else if (currentStatus === 'ACTIVE') {
      complexMatches = complex.approvedStatus === 'APPROVED' && complex.complexStatus === 'AVAILABLE';
    }

    const complexFacilities = venues.filter(v => v.nodeType === 'FACILITY' && v.complexId === complex.complexId);
    return complexMatches || complexFacilities.length > 0;
  });

  return (
    <div className="container mx-auto px-4 py-8 max-w-6xl">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6">
        <div>
          <h1 className="text-3xl font-extrabold text-slate-900 dark:text-white">Sơ đồ sân của tôi</h1>
          <p className="text-slate-500 text-sm mt-1">Quản lý phân cấp 3 tầng: Tổ hợp → Khu sân → Sân lẻ</p>
        </div>
        <Button onClick={() => router.push("/owner/venues/new")} className="shadow-lg shadow-primary/10 shrink-0">
          <Plus className="mr-2 h-5 w-5" />
          Đăng ký Tổ hợp mới
        </Button>
      </div>

      <div className="mb-8 flex justify-between items-center gap-4 flex-wrap">
        <Tabs value={currentStatus || "ALL"} onValueChange={(val) => handleStatusChange(val === "ALL" ? "" : val)}>
          <TabsList className="bg-slate-100 dark:bg-muted p-1">
            <TabsTrigger value="ALL">Tất cả</TabsTrigger>
            <TabsTrigger value="ACTIVE">Đang hoạt động</TabsTrigger>
            <TabsTrigger value="PENDING">Đang chờ duyệt</TabsTrigger>
            <TabsTrigger value="SUSPENDED">Tạm dừng</TabsTrigger>
          </TabsList>
        </Tabs>
      </div>

      <div className={`transition-opacity duration-200 ${isPending ? "opacity-60 pointer-events-none" : ""}`}>
        {loading ? (
        <div className="flex justify-center items-center py-20">
          <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
        </div>
      ) : complexes.length === 0 ? (
        <div className="text-center py-20 text-muted-foreground bg-white dark:bg-card/50 rounded-3xl border border-dashed p-6 shadow-sm">
          <Building2 className="h-16 w-16 mx-auto mb-4 text-slate-300" />
          <p className="text-lg font-bold mb-4">Bạn chưa đăng ký tổ hợp sân nào.</p>
          <Button onClick={() => router.push("/owner/venues/new")}>Đăng ký ngay</Button>
        </div>
      ) : renderedComplexes.length === 0 ? (
        <div className="text-center py-20 text-muted-foreground bg-white dark:bg-card/50 rounded-3xl border border-dashed p-6 shadow-sm">
          <Building2 className="h-16 w-16 mx-auto mb-4 text-slate-300" />
          <p className="text-lg font-bold mb-4">Không tìm thấy tổ hợp nào khớp với bộ lọc.</p>
        </div>
      ) : (
        <div className="space-y-6">
          {renderedComplexes.map((complex) => {
            const isComplexCollapsed = collapsedComplexes[complex.complexId];
            const complexFacilities = venues.filter(v => v.nodeType === 'FACILITY' && v.complexId === complex.complexId);

            return (
              <Card key={complex.complexId} id={`complex-${complex.complexId}`} className="border-slate-100 dark:border-border overflow-hidden shadow-sm hover:shadow-md transition-all duration-300">
                {/* L1: Complex Node Header */}
                <div className="bg-slate-50/70 dark:bg-muted/40 p-5 flex flex-col md:flex-row md:items-center justify-between gap-4 border-b border-slate-100 dark:border-border">
                  <div className="flex items-start gap-3 flex-1 min-w-0">
                    <button
                      onClick={() => toggleComplex(complex.complexId)}
                      className="p-1 hover:bg-slate-200 dark:hover:bg-muted rounded text-slate-500 mt-1 shrink-0"
                    >
                      {isComplexCollapsed ? <ChevronRight className="h-5 w-5" /> : <ChevronDown className="h-5 w-5" />}
                    </button>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2.5">
                        <Building2 className="h-5 w-5 text-primary shrink-0" />
                        <h3 className="font-extrabold text-xl text-slate-900 dark:text-white leading-tight break-words">
                          {complex.name}
                        </h3>
                      </div>
                      <div className="flex items-center gap-1.5 mt-1.5 flex-wrap">
                        {getApprovedStatusBadge(complex.approvedStatus)}
                        {getStatusBadge(complex.complexStatus)}
                      </div>
                      <p className="text-xs text-muted-foreground mt-1.5 flex items-start gap-1.5 whitespace-normal break-words">
                        <span className="shrink-0">📍</span>
                        <span>{complex.address}</span>
                      </p>
                    </div>
                  </div>

                  {/* Actions for Complex */}
                  <div className="flex flex-wrap gap-2 md:self-center pl-8 md:pl-0">
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setEditingComplex(complex)}
                      className="text-xs font-semibold"
                    >
                      <Edit className="mr-1.5 h-3.5 w-3.5" />
                      Chỉnh sửa
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => {
                        setBulkComplexId(complex.complexId);
                        const complexFacilityIds = complexFacilities.map(f => f.stadiumId);
                        const complexCourts = venues.filter(v => v.nodeType === 'COURT' && complexFacilityIds.includes(v.parentStadiumId || 0));
                        setBulkCourtsList(complexCourts);
                      }}
                      className="text-xs font-semibold"
                    >
                      <Sliders className="mr-1.5 h-3.5 w-3.5" />
                      Cấu hình giờ hàng loạt
                    </Button>
                    <Button
                      size="sm"
                      onClick={() => setActiveComplexIdForFacility(complex.complexId)}
                      className="text-xs font-semibold bg-emerald-600 hover:bg-emerald-700 text-white"
                    >
                      <Plus className="mr-1.5 h-3.5 w-3.5" />
                      Thêm Khu sân (L2)
                    </Button>

                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <Button variant="outline" size="icon" className="h-8 w-8 shrink-0">
                          <MoreVertical className="h-4 w-4" />
                        </Button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="end" className="w-52">
                        <DropdownMenuItem onClick={() => setMaintenanceTarget({ type: 'complex', id: complex.complexId, name: complex.name })}>
                          <Wrench className="mr-2 h-4 w-4" />
                          Đặt bảo trì
                        </DropdownMenuItem>
                        {complex.complexStatus === 'AVAILABLE' ? (
                          <DropdownMenuItem onClick={() => setSuspendVenue({ id: complex.complexId, name: complex.name, type: 'complex' })}>
                            <Pause className="mr-2 h-4 w-4" />
                            Tạm dừng tổ hợp
                          </DropdownMenuItem>
                        ) : complex.complexStatus === 'MAINTENANCE' ? (
                          <DropdownMenuItem onClick={() => setActivateVenue({ id: complex.complexId, name: complex.name, type: 'complex' })}>
                            <PlayCircle className="mr-2 h-4 w-4" />
                            Hoạt động tổ hợp
                          </DropdownMenuItem>
                        ) : null}
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </div>
                </div>

                {/* L2 & L3 Nodes (Complex Content) */}
                {!isComplexCollapsed && (
                  <CardContent className="p-0">
                    {complexFacilities.length === 0 ? (
                      <div className="text-center py-8 text-muted-foreground text-sm">
                        Chưa có khu sân (L2) nào thuộc tổ hợp này. Hãy thêm mới môn thể thao!
                      </div>
                    ) : (
                      <div className="divide-y divide-slate-100 dark:divide-border">
                        {complexFacilities.map((facility) => {
                          const isFacilityCollapsed = collapsedFacilities[facility.stadiumId];
                          const facilityCourts = venues.filter(v => v.nodeType === 'COURT' && v.parentStadiumId === facility.stadiumId);

                          return (
                            <div key={facility.stadiumId} id={`facility-${facility.stadiumId}`} className="bg-white dark:bg-card transition-all duration-300">
                              {/* L2: Facility Node Header */}
                              <div className="p-4 pl-8 md:pl-12 flex flex-col md:flex-row md:items-center justify-between gap-4 bg-slate-50/20">
                                <div className="flex items-start gap-3 min-w-0 flex-1">
                                  <button
                                    onClick={() => toggleFacility(facility.stadiumId)}
                                    className="p-1 hover:bg-slate-100 dark:hover:bg-muted rounded text-slate-400 shrink-0 mt-0.5"
                                  >
                                    {isFacilityCollapsed ? <ChevronRight className="h-4.5 w-4.5" /> : <ChevronDown className="h-4.5 w-4.5" />}
                                  </button>
                                  <div className="min-w-0 flex-1">
                                    <div className="flex items-center gap-2">
                                      <Trophy className="h-4.5 w-4.5 text-blue-500 shrink-0" />
                                      <span className="font-bold text-slate-800 dark:text-white break-words">
                                        {facility.stadiumName}
                                      </span>
                                    </div>
                                    <div className="flex items-center gap-1.5 mt-1 flex-wrap">
                                      <Badge variant="secondary" className="text-[10px] font-semibold bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400 border-0 uppercase">
                                        {facility.sportName}
                                      </Badge>
                                      <span className="text-xs text-muted-foreground">
                                        ({facility.openTime?.substring(0, 5)} - {facility.closeTime?.substring(0, 5)})
                                      </span>
                                      {getApprovedStatusBadge(facility.approvedStatus)}
                                      {getStatusBadge(facility.stadiumStatus)}
                                      {facility.stadiumStatus === 'AVAILABLE' && facility.underMaintenanceToday && (
                                        <Badge className="bg-red-100 text-red-700 hover:bg-red-100 text-[10px] font-semibold">
                                          Đang bảo trì hôm nay
                                        </Badge>
                                      )}
                                    </div>
                                  </div>
                                </div>

                                <div className="flex items-center gap-1.5 pl-8 md:pl-0">
                                  <Button
                                    variant="outline"
                                    size="sm"
                                    onClick={() => {
                                      setBulkFacilityId(facility.stadiumId);
                                      setBulkCourtsList(facilityCourts);
                                    }}
                                    className="text-xs font-semibold h-8"
                                  >
                                    <Sliders className="mr-1.5 h-3.5 w-3.5" />
                                    Cấu hình giờ
                                  </Button>
                                  <Button
                                    variant="outline"
                                    size="sm"
                                    onClick={() => setActiveFacilityForCourt(facility)}
                                    className="text-xs font-semibold h-8 border-primary/20 text-primary hover:bg-primary/5"
                                  >
                                    <Plus className="mr-1.5 h-3.5 w-3.5" />
                                    Thêm sân lẻ (L3)
                                  </Button>

                                  <DropdownMenu>
                                    <DropdownMenuTrigger asChild>
                                      <Button variant="ghost" size="icon" className="h-8 w-8 shrink-0">
                                        <MoreVertical className="h-4.5 w-4.5" />
                                      </Button>
                                    </DropdownMenuTrigger>
                                    <DropdownMenuContent align="end" className="w-44">
                                      <DropdownMenuItem onClick={() => setEditingFacility(facility)}>
                                        <Edit className="mr-2 h-4 w-4" />
                                        Chỉnh sửa
                                      </DropdownMenuItem>

                                      <DropdownMenuItem
                                        onClick={() => {
                                          setSelectedVenueForAccessory({ id: facility.stadiumId, name: facility.stadiumName });
                                          setIsAccessoryOpen(true);
                                        }}
                                      >
                                        <Package className="mr-2 h-4 w-4" />
                                        Quản lý phụ kiện
                                      </DropdownMenuItem>

                                      <DropdownMenuItem onClick={() => setMaintenanceTarget({ type: 'stadium', id: facility.stadiumId, name: facility.stadiumName })}>
                                        <Wrench className="mr-2 h-4 w-4" />
                                        Đặt bảo trì
                                      </DropdownMenuItem>

                                      {facility.stadiumStatus === 'AVAILABLE' ? (
                                        <DropdownMenuItem onClick={() => setSuspendVenue({ id: facility.stadiumId, name: facility.stadiumName })}>
                                          <Pause className="mr-2 h-4 w-4" />
                                          Tạm dừng
                                        </DropdownMenuItem>
                                      ) : facility.stadiumStatus === 'MAINTENANCE' ? (
                                        <DropdownMenuItem onClick={() => setActivateVenue({ id: facility.stadiumId, name: facility.stadiumName })}>
                                          <PlayCircle className="mr-2 h-4 w-4" />
                                          Hoạt động
                                        </DropdownMenuItem>
                                      ) : null}

                                      {facility.stadiumStatus !== 'CLOSED' && (
                                        <DropdownMenuItem
                                          onClick={() => {
                                            setDeleteVenue({ id: facility.stadiumId, name: facility.stadiumName });
                                            setDeleteConfirmText("");
                                          }}
                                          className="text-destructive focus:text-destructive"
                                        >
                                          <Trash className="mr-2 h-4 w-4" />
                                          Xóa khu sân
                                        </DropdownMenuItem>
                                      )}
                                    </DropdownMenuContent>
                                  </DropdownMenu>
                                </div>
                              </div>

                              {/* L3: Court Table Content */}
                              {!isFacilityCollapsed && (
                                <div className="pl-12 md:pl-20 pr-4 pb-4">
                                  {facilityCourts.length === 0 ? (
                                    <div className="text-xs text-muted-foreground py-4 text-center border border-dashed rounded-lg bg-slate-50/20">
                                      Chưa đăng ký sân lẻ (L3) nào cho khu vực này.
                                    </div>
                                  ) : (
                                    <div className="border border-slate-100 dark:border-border rounded-xl overflow-hidden bg-white dark:bg-card">
                                      <Table className="table-fixed">
                                        <TableHeader className="bg-slate-50/50">
                                          <TableRow>
                                            <TableHead className="w-2/5 text-xs font-bold uppercase tracking-wider py-2">Tên sân lẻ</TableHead>
                                            <TableHead className="w-1/5 text-xs font-bold uppercase tracking-wider py-2">Giá thuê / Giờ</TableHead>
                                            <TableHead className="w-1/4 text-xs font-bold uppercase tracking-wider py-2">Trạng thái hoạt động</TableHead>
                                            <TableHead className="w-[15%] text-xs font-bold uppercase tracking-wider py-2 text-right">Tác vụ</TableHead>
                                          </TableRow>
                                        </TableHeader>
                                        <TableBody>
                                          {facilityCourts.map(court => (
                                            <TableRow key={court.stadiumId} id={`court-${court.stadiumId}`} className="hover:bg-slate-50/30 transition-all duration-300">
                                              <TableCell className="font-semibold text-slate-800 dark:text-slate-200 truncate">
                                                <div className="flex flex-col gap-1 items-start">
                                                  <span className="truncate">{court.stadiumName}</span>
                                                  {court.footballFieldType && (
                                                    <Badge variant="outline" className="text-[10px] py-0 font-medium">
                                                      {court.footballFieldType === 'FIVE_A_SIDE' && 'Sân 5'}
                                                      {court.footballFieldType === 'SEVEN_A_SIDE' && 'Sân 7'}
                                                      {court.footballFieldType === 'ELEVEN_A_SIDE' && 'Sân 11'}
                                                      {court.footballFieldType === 'FUTSAL' && 'Sân Futsal'}
                                                    </Badge>
                                                  )}
                                                </div>
                                              </TableCell>
                                              <TableCell className="font-extrabold text-slate-900 dark:text-white text-sm">
                                                {court.pricePerHour.toLocaleString('vi-VN')}₫
                                              </TableCell>
                                              <TableCell className="whitespace-normal">
                                                <div className="flex flex-col gap-1 items-start">
                                                  {getStatusBadge(court.stadiumStatus)}
                                                  {court.stadiumStatus === 'AVAILABLE' && court.underMaintenanceToday && (
                                                    <Badge className="bg-red-100 text-red-700 hover:bg-red-100 text-[10px] font-semibold whitespace-nowrap">
                                                      Đang bảo trì hôm nay
                                                    </Badge>
                                                  )}
                                                </div>
                                              </TableCell>
                                              <TableCell className="text-right">
                                                <div className="flex justify-end gap-1">
                                                  <Button
                                                    variant="ghost"
                                                    size="icon"
                                                    className="h-8 w-8 shrink-0 text-slate-500 hover:text-slate-900"
                                                    onClick={() => router.push(`/owner/venues/${court.stadiumId}/slots`)}
                                                    title="Xem lịch đặt sân"
                                                  >
                                                    <CalendarDays className="h-4.5 w-4.5" />
                                                  </Button>
                                                  <DropdownMenu>
                                                    <DropdownMenuTrigger asChild>
                                                      <Button variant="ghost" size="icon" className="h-8 w-8 shrink-0">
                                                        <MoreVertical className="h-4.5 w-4.5" />
                                                      </Button>
                                                    </DropdownMenuTrigger>
                                                    <DropdownMenuContent align="end" className="w-44">
                                                      <DropdownMenuItem onClick={() => router.push(`/owner/venues/${court.stadiumId}/edit`)}>
                                                        <Edit className="mr-2 h-4 w-4" />
                                                        Chỉnh sửa
                                                      </DropdownMenuItem>
                                                      <DropdownMenuItem onClick={() => setMaintenanceTarget({ type: 'stadium', id: court.stadiumId, name: court.stadiumName })}>
                                                        <Wrench className="mr-2 h-4 w-4" />
                                                        Đặt bảo trì
                                                      </DropdownMenuItem>
                                                      {court.stadiumStatus === 'AVAILABLE' ? (
                                                        <DropdownMenuItem onClick={() => setSuspendVenue({ id: court.stadiumId, name: court.stadiumName })}>
                                                          <Pause className="mr-2 h-4 w-4" />
                                                          Tạm dừng
                                                        </DropdownMenuItem>
                                                      ) : court.stadiumStatus === 'MAINTENANCE' ? (
                                                        <DropdownMenuItem onClick={() => setActivateVenue({ id: court.stadiumId, name: court.stadiumName })}>
                                                          <PlayCircle className="mr-2 h-4 w-4" />
                                                          Hoạt động
                                                        </DropdownMenuItem>
                                                      ) : null}
                                                      {court.stadiumStatus !== 'CLOSED' && (
                                                        <DropdownMenuItem
                                                          className="text-destructive focus:bg-destructive focus:text-destructive-foreground"
                                                          onClick={() => setDeleteVenue({ id: court.stadiumId, name: court.stadiumName })}
                                                        >
                                                          <Trash className="mr-2 h-4 w-4" />
                                                          Xóa sân
                                                        </DropdownMenuItem>
                                                      )}
                                                    </DropdownMenuContent>
                                                  </DropdownMenu>
                                                </div>
                                              </TableCell>
                                            </TableRow>
                                          ))}
                                        </TableBody>
                                      </Table>
                                    </div>
                                  )}
                                </div>
                              )}
                            </div>
                          );
                        })}
                      </div>
                    )}
                  </CardContent>
                )}
              </Card>
            );
          })}
        </div>
      )}
      </div>

      {selectedVenueForAccessory && (
        <AccessoryManagerDialog
          stadiumId={selectedVenueForAccessory.id}
          stadiumName={selectedVenueForAccessory.name}
          isOpen={isAccessoryOpen}
          onClose={() => {
            setIsAccessoryOpen(false);
            setSelectedVenueForAccessory(null);
          }}
        />
      )}

      {/* Suspend Modal */}
      <Dialog open={!!suspendVenue} onOpenChange={(open) => !open && setSuspendVenue(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Xác nhận tạm dừng {suspendVenue?.type === 'complex' ? 'tổ hợp' : 'sân'}</DialogTitle>
            <DialogDescription>
              Bạn có chắc chắn muốn tạm dừng hoạt động {suspendVenue?.type === 'complex' ? 'tổ hợp' : 'sân'} <strong>{suspendVenue?.name}</strong> không?
              {suspendVenue?.type === 'complex'
                ? ' Toàn bộ khu sân và sân lẻ bên trong tổ hợp sẽ chuyển sang bảo trì và khách hàng không thể đặt sân mới.'
                : ' Trạng thái sân sẽ chuyển sang bảo trì và khách hàng không thể đặt sân mới.'}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setSuspendVenue(null)} disabled={isSubmitting}>Hủy</Button>
            <Button onClick={handleSuspend} disabled={isSubmitting}>
              {isSubmitting && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              Xác nhận
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Activate Modal */}
      <Dialog open={!!activateVenue} onOpenChange={(open) => !open && setActivateVenue(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Xác nhận mở lại {activateVenue?.type === 'complex' ? 'tổ hợp' : 'sân'}</DialogTitle>
            <DialogDescription>
              {activateVenue?.type === 'complex' ? 'Tổ hợp' : 'Sân'} <strong>{activateVenue?.name}</strong> sẽ được chuyển sang trạng thái hoạt động và khách hàng có thể tiếp tục đặt sân.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setActivateVenue(null)} disabled={isSubmitting}>Hủy</Button>
            <Button onClick={handleActivate} disabled={isSubmitting}>
              {isSubmitting && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              Xác nhận
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete Modal */}
      <Dialog open={!!deleteVenue} onOpenChange={(open) => !open && setDeleteVenue(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Xóa sân (Nguy hiểm)</DialogTitle>
            <DialogDescription className="text-destructive">
              Hành động này sẽ đóng cửa sân <strong>{deleteVenue?.name}</strong> vĩnh viễn và hủy tất cả các lịch đặt chưa diễn ra. Hành động này không thể hoàn tác!
            </DialogDescription>
          </DialogHeader>
          <div className="py-4">
            <p className="text-sm mb-2">Vui lòng nhập <strong>DELETE</strong> để xác nhận:</p>
            <Input 
              value={deleteConfirmText}
              onChange={(e) => setDeleteConfirmText(e.target.value)}
              placeholder="DELETE"
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => { setDeleteVenue(null); setDeleteConfirmText(""); }} disabled={isSubmitting}>Hủy</Button>
            <Button variant="destructive" onClick={handleDelete} disabled={isSubmitting || deleteConfirmText !== "DELETE"}>
              {isSubmitting && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              Xóa sân
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        isOpen={isConfirmOpen}
        onClose={closeConfirm}
        onConfirm={executeConfirm}
        isLoading={isActionLoading}
        title={confirmOptions?.title || ""}
        description={confirmOptions?.description || ""}
        confirmText={confirmOptions?.confirmText}
        variant={confirmOptions?.variant}
      />

      {/* Modals & Dialogs for Quick creations */}
      <QuickCreateFacilityDialog
        isOpen={activeComplexIdForFacility !== null}
        onClose={() => setActiveComplexIdForFacility(null)}
        complexId={activeComplexIdForFacility}
        complexSportTypeIds={
          complexes.find(c => c.complexId === activeComplexIdForFacility)?.sportTypeIds || []
        }
        onSuccess={fetchTreeData}
      />

      <EditComplexDialog
        isOpen={editingComplex !== null}
        onClose={() => setEditingComplex(null)}
        complex={editingComplex}
        onSuccess={fetchTreeData}
      />

      <EditFacilityDialog
        isOpen={editingFacility !== null}
        onClose={() => setEditingFacility(null)}
        facility={editingFacility}
        complexSportTypeIds={
          complexes.find(c => c.complexId === editingFacility?.complexId)?.sportTypeIds || []
        }
        onSuccess={fetchTreeData}
      />

      <QuickCreateCourtDialog
        isOpen={activeFacilityForCourt !== null}
        onClose={() => setActiveFacilityForCourt(null)}
        parentFacility={activeFacilityForCourt}
        onSuccess={fetchTreeData}
      />

      <BulkTimeSlotConfigDialog
        isOpen={bulkFacilityId !== null || bulkComplexId !== null}
        onClose={() => {
          setBulkFacilityId(null);
          setBulkComplexId(null);
          setBulkCourtsList([]);
        }}
        facilityId={bulkFacilityId}
        complexId={bulkComplexId}
        courts={bulkCourtsList}
        onSuccess={fetchTreeData}
      />

      {maintenanceTarget && (
        <MaintenanceScheduleDialog
          isOpen={maintenanceTarget !== null}
          onClose={() => setMaintenanceTarget(null)}
          targetType={maintenanceTarget.type}
          targetId={maintenanceTarget.id}
          targetName={maintenanceTarget.name}
          onSuccess={fetchTreeData}
        />
      )}
    </div>
  );
}
