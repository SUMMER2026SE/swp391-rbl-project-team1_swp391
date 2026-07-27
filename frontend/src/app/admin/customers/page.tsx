"use client";

import { useState } from "react";
import { usePathname } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { format } from "date-fns";
import { Search, Loader2 } from "lucide-react";
import { toast } from "sonner";
import api from "@/lib/api";

import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import { useDebounceValue } from "usehooks-ts";
import { useConfirm } from "@/hooks/useConfirm";
import { ConfirmDialog } from "@/components/common/ConfirmDialog";


export interface AdminCustomerResponse {
  userId: number;
  fullName: string;
  email: string;
  phoneNumber: string;
  accountStatus: "ACTIVE" | "BLOCKED" | "PENDING";
  createdAt: string;
}

interface PageResponse<T> {
  content: T[];
  totalPages: number;
  totalElements: number;
}

interface ApiResponse<T> {
  code: number;
  message: string;
  result: T;
}

interface CreateCustomerRequest {
  fullName: string;
  email: string;
  phoneNumber?: string;
  phone?: string;
  password?: string;
  confirmPassword?: string;
}

interface ApiError {
  response?: {
    data?: { message?: string } | Record<string, string>;
    status?: number;
  };
  message?: string;
}

const fetchCustomers = async (page: number, size: number, search: string, status: string) => {
  const params: Record<string, string | number> = { page, pageSize: size };
  if (search) params.search = search;
  if (status && status !== "ALL") params.accountStatus = status;

  const { data } = await api.get<ApiResponse<PageResponse<AdminCustomerResponse>>>("/admin/customers", { params });
  return data.result;
};



function AdminCustomersContent() {
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState("ALL");
  
  const [isAddModalOpen, setIsAddModalOpen] = useState(false);
  const [newCustomer, setNewCustomer] = useState({ fullName: "", email: "", phoneNumber: "", password: "", confirmPassword: "" });

  const [debouncedSearch] = useDebounceValue(search, 500);

  const { data, isLoading, isError } = useQuery({
    queryKey: ["admin-customers", page, debouncedSearch, statusFilter],
    queryFn: () => fetchCustomers(page, 10, debouncedSearch, statusFilter),
  });

  const addCustomerMutation = useMutation({
    mutationFn: async (customerData: CreateCustomerRequest) => {
      // Gọi API register của hệ thống
      const { data } = await api.post("/auth/register", customerData);
      return data;
    },
    onSuccess: () => {
      toast.success("Thêm khách hàng thành công! Đã gửi email xác thực.");
      queryClient.invalidateQueries({ queryKey: ["admin-customers"] });
      setIsAddModalOpen(false);
      setNewCustomer({ fullName: "", email: "", phoneNumber: "", password: "", confirmPassword: "" });
    },
    onError: (error: ApiError) => {
      // API Backend có thể trả về error.response.data với cấu trúc map lỗi validation
      if (error.response?.data && typeof error.response.data === 'object' && !error.response.data.message) {
         // Nếu backend trả về map field errors (VD: { phone: "...", password: "..." })
         const msgs = Object.values(error.response.data).join("; ");
         toast.error(msgs || "Lỗi dữ liệu đầu vào");
      } else {
         toast.error(error?.response?.data?.message || error?.message || "Có lỗi xảy ra khi thêm khách hàng.");
      }
    }
  });

  const handleAddCustomer = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newCustomer.email || !newCustomer.fullName || !newCustomer.password) {
      toast.error("Vui lòng nhập đủ họ tên, email và mật khẩu!");
      return;
    }
    if (newCustomer.password !== newCustomer.confirmPassword) {
      toast.error("Mật khẩu xác nhận không khớp!");
      return;
    }
    addCustomerMutation.mutate({
      fullName: newCustomer.fullName,
      email: newCustomer.email,
      phone: newCustomer.phoneNumber,
      password: newCustomer.password,
      confirmPassword: newCustomer.confirmPassword
    });
  };

  // Lock/Unlock dialog state
  const [lockDialogOpen, setLockDialogOpen] = useState(false);
  const [selectedCustomer, setSelectedCustomer] = useState<AdminCustomerResponse | null>(null);
  const [lockReason, setLockReason] = useState("");

  const lockUnlockMutation = useMutation({
    mutationFn: async ({ id, enabled, reason }: { id: number; enabled: boolean; reason?: string }) => {
      const { data } = await api.patch(`/admin/customers/${id}/lock`, { enabled, reason });
      return data;
    },
    onSuccess: (data, variables) => {
      toast.success(data.message || (variables.enabled ? "Đã mở khóa tài khoản khách hàng!" : "Đã khóa tài khoản khách hàng!"));
      queryClient.invalidateQueries({ queryKey: ["admin-customers"] });
      setLockDialogOpen(false);
      setSelectedCustomer(null);
      setLockReason("");
    },
    onError: (error: ApiError) => {
      toast.error(error?.response?.data?.message || error?.message || "Có lỗi xảy ra.");
    }
  });

  const openLockModal = (customer: AdminCustomerResponse) => {
    setSelectedCustomer(customer);
    setLockReason("");
    setLockDialogOpen(true);
  };

  return (
    <div className="space-y-6">
      <div className="flex justify-end">
        
        <Dialog open={isAddModalOpen} onOpenChange={setIsAddModalOpen}>
          <DialogTrigger asChild>
            <Button className="bg-primary text-white shadow-sm hover:bg-primary/90">
              + Thêm Khách hàng mới
            </Button>
          </DialogTrigger>
          <DialogContent className="sm:max-w-[425px]">
            <form onSubmit={handleAddCustomer}>
              <DialogHeader>
                <DialogTitle>Thêm Khách hàng</DialogTitle>
                <DialogDescription>
                  Tạo tài khoản mới cho khách hàng. Vui lòng điền đầy đủ thông tin bên dưới.
                </DialogDescription>
              </DialogHeader>
              <div className="grid gap-4 py-4">
                <div className="grid grid-cols-4 items-center gap-4">
                  <label htmlFor="fullName" className="text-right text-sm font-medium">Họ & Tên</label>
                  <Input id="fullName" className="col-span-3" value={newCustomer.fullName} onChange={e => setNewCustomer({...newCustomer, fullName: e.target.value})} placeholder="Nguyễn Văn A" required />
                </div>
                <div className="grid grid-cols-4 items-center gap-4">
                  <label htmlFor="email" className="text-right text-sm font-medium">Email</label>
                  <Input id="email" type="email" className="col-span-3" value={newCustomer.email} onChange={e => setNewCustomer({...newCustomer, email: e.target.value})} placeholder="email@example.com" required />
                </div>
                <div className="grid grid-cols-4 items-center gap-4">
                  <label htmlFor="phone" className="text-right text-sm font-medium">SĐT</label>
                  <Input id="phone" className="col-span-3" value={newCustomer.phoneNumber} onChange={e => setNewCustomer({...newCustomer, phoneNumber: e.target.value})} placeholder="0912345678" required />
                </div>
                <div className="grid grid-cols-4 items-center gap-4">
                  <label htmlFor="password" className="text-right text-sm font-medium">Mật khẩu</label>
                  <Input id="password" type="password" className="col-span-3" value={newCustomer.password} onChange={e => setNewCustomer({...newCustomer, password: e.target.value})} placeholder="********" required />
                </div>
                <div className="grid grid-cols-4 items-center gap-4">
                  <label htmlFor="confirmPassword" className="text-right text-sm font-medium">Xác nhận MK</label>
                  <Input id="confirmPassword" type="password" className="col-span-3" value={newCustomer.confirmPassword} onChange={e => setNewCustomer({...newCustomer, confirmPassword: e.target.value})} placeholder="********" required />
                </div>
              </div>
              <DialogFooter>
                <Button type="button" variant="outline" onClick={() => setIsAddModalOpen(false)}>Hủy</Button>
                <Button type="submit" disabled={addCustomerMutation.isPending}>
                  {addCustomerMutation.isPending && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}
                  Lưu
                </Button>
              </DialogFooter>
            </form>
          </DialogContent>
        </Dialog>
      </div>

      <div className="flex items-center gap-4 bg-white p-4 rounded-lg shadow-sm border border-gray-100">
        <div className="relative flex-1 max-w-md">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-500" />
          <Input
            placeholder="Tìm kiếm theo tên, email..."
            className="pl-9 bg-gray-50"
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              setPage(0);
            }}
          />
        </div>
        <Select
          value={statusFilter}
          onValueChange={(val) => {
            setStatusFilter(val);
            setPage(0);
          }}
        >
          <SelectTrigger className="w-[200px] bg-gray-50">
            <SelectValue placeholder="Trạng thái" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">Tất cả trạng thái</SelectItem>
            <SelectItem value="ACTIVE">Hoạt động (Active)</SelectItem>
            <SelectItem value="BLOCKED">Đã khóa (Locked)</SelectItem>
          </SelectContent>
        </Select>
      </div>

      <div className="bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden">
        <Table>
          <TableHeader className="bg-gray-50/80">
            <TableRow>
              <TableHead className="font-semibold text-gray-900">Tên khách hàng</TableHead>
              <TableHead className="font-semibold text-gray-900">Email</TableHead>
              <TableHead className="font-semibold text-gray-900">Số điện thoại</TableHead>
              <TableHead className="font-semibold text-gray-900">Ngày tạo</TableHead>
              <TableHead className="font-semibold text-gray-900">Trạng thái</TableHead>
              <TableHead className="font-semibold text-gray-900 text-right">Hành động</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableRow>
                <TableCell colSpan={6} className="h-32 text-center">
                  <Loader2 className="h-6 w-6 animate-spin mx-auto text-primary" />
                </TableCell>
              </TableRow>
            ) : isError ? (
              <TableRow>
                <TableCell colSpan={6} className="h-32 text-center text-red-500 font-medium">
                  Đã xảy ra lỗi khi tải dữ liệu! Vui lòng thử lại.
                </TableCell>
              </TableRow>
            ) : data?.content?.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6} className="h-32 text-center text-gray-500">
                  Không tìm thấy khách hàng nào phù hợp với bộ lọc.
                </TableCell>
              </TableRow>
            ) : (
              data?.content?.map((customer) => (
                <TableRow key={customer.userId} className="hover:bg-gray-50/50 transition-colors">
                  <TableCell className="font-medium text-gray-900">{customer.fullName}</TableCell>
                  <TableCell className="text-gray-600">{customer.email}</TableCell>
                  <TableCell className="text-gray-600">{customer.phoneNumber || "N/A"}</TableCell>
                  <TableCell className="text-gray-600">
                    {customer.createdAt
                      ? format(new Date(customer.createdAt), "dd/MM/yyyy HH:mm")
                      : "N/A"}
                  </TableCell>
                  <TableCell>
                    {customer.accountStatus === "ACTIVE" ? (
                      <Badge variant="default" className="bg-emerald-500 hover:bg-emerald-600 text-white">
                        Active
                      </Badge>
                    ) : customer.accountStatus === "BLOCKED" ? (
                      <Badge variant="destructive" className="bg-rose-500">Locked</Badge>
                    ) : (
                      <Badge variant="secondary">Pending</Badge>
                    )}
                  </TableCell>
                  <TableCell className="text-right">
                    <Button
                      variant={customer.accountStatus === "ACTIVE" ? "destructive" : "default"}
                      size="sm"
                      onClick={() => openLockModal(customer)}
                      disabled={lockUnlockMutation.isPending && lockUnlockMutation.variables?.id === customer.userId}
                      className={customer.accountStatus !== "ACTIVE" ? "bg-emerald-500 hover:bg-emerald-600" : ""}
                    >
                      {lockUnlockMutation.isPending && lockUnlockMutation.variables?.id === customer.userId && (
                        <Loader2 className="w-3 h-3 mr-1 animate-spin" />
                      )}
                      {customer.accountStatus === "ACTIVE" ? "Khóa" : "Mở khóa"}
                    </Button>
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>

      <div className="flex justify-end items-center gap-4">
        <span className="text-sm text-gray-500 font-medium">
          Trang {page + 1} / {data?.totalPages || 1}
        </span>
        <div className="flex gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={page === 0}
            onClick={() => setPage((p) => p - 1)}
            className="w-20"
          >
            Trước
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={!data || page >= (data.totalPages - 1)}
            onClick={() => setPage((p) => p + 1)}
            className="w-20"
          >
            Sau
          </Button>
        </div>
      </div>

      {/* Dialog Modal Khóa / Mở khóa tài khoản khách hàng */}
      <Dialog open={lockDialogOpen} onOpenChange={setLockDialogOpen}>
        <DialogContent className="sm:max-w-[425px]">
          <DialogHeader>
            <DialogTitle>
              {selectedCustomer?.accountStatus === "ACTIVE"
                ? `Khóa tài khoản ${selectedCustomer?.fullName}`
                : `Mở khóa tài khoản ${selectedCustomer?.fullName}`}
            </DialogTitle>
            <DialogDescription>
              {selectedCustomer?.accountStatus === "ACTIVE"
                ? `Vui lòng nhập lý do khóa tài khoản khách hàng (${selectedCustomer?.email}). Lý do này sẽ được gửi tới Email của khách hàng.`
                : `Xác nhận mở khóa tài khoản khách hàng (${selectedCustomer?.email}).`}
            </DialogDescription>
          </DialogHeader>

          {selectedCustomer?.accountStatus === "ACTIVE" && (
            <div className="grid gap-2 py-2">
              <Label htmlFor="customerLockReason" className="text-sm font-medium">
                Lý do khóa tài khoản <span className="text-rose-500">*</span>
              </Label>
              <Textarea
                id="customerLockReason"
                value={lockReason}
                onChange={(e) => setLockReason(e.target.value)}
                placeholder="Nhập lý do khóa tài khoản (VD: Vi phạm quy định đặt sân, spam đơn...)"
                rows={3}
              />
            </div>
          )}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setLockDialogOpen(false)}>
              Hủy
            </Button>
            <Button
              type="button"
              variant={selectedCustomer?.accountStatus === "ACTIVE" ? "destructive" : "default"}
              disabled={
                lockUnlockMutation.isPending ||
                (selectedCustomer?.accountStatus === "ACTIVE" && !lockReason.trim())
              }
              onClick={() => {
                if (!selectedCustomer) return;
                const isCurrentlyActive = selectedCustomer.accountStatus === "ACTIVE";
                lockUnlockMutation.mutate({
                  id: selectedCustomer.userId,
                  enabled: !isCurrentlyActive,
                  reason: isCurrentlyActive ? lockReason.trim() : undefined,
                });
              }}
              className={selectedCustomer?.accountStatus !== "ACTIVE" ? "bg-emerald-600 hover:bg-emerald-700" : ""}
            >
              {lockUnlockMutation.isPending && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}
              {selectedCustomer?.accountStatus === "ACTIVE" ? "Xác nhận khóa" : "Xác nhận mở khóa"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

export default function AdminCustomersPage() {
  const pathname = usePathname();

  if (pathname.startsWith("/admin/users")) {
    return <AdminCustomersContent />;
  }

  return (
    <div className="p-8 space-y-6">
      <div className="flex justify-between items-center">
        <h1 className="text-2xl font-bold tracking-tight text-gray-900">Quản lý Khách hàng</h1>
      </div>
      <AdminCustomersContent />
    </div>
  );
}
