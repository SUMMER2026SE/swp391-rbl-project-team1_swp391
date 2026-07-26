'use client'

import { useState, Suspense } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { signIn } from "next-auth/react";
import { 
  Mail, 
  Lock, 
  User, 
  Chrome, 
  Phone, 
  Loader2, 
  Eye, 
  EyeOff,
  Building,
  FileText,
  MapPin
} from "lucide-react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import { Separator } from "@/components/ui/separator";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";

import api from "@/lib/api";
import { 
  registerSchema, 
  registerOwnerSchema, 
  type RegisterFormValues, 
  type RegisterOwnerFormValues 
} from "@/lib/validations/auth.schema";
import { PasswordStrengthIndicator } from "@/components/auth/PasswordStrengthIndicator";
import { DocumentUploader } from "@/components/shared/DocumentUploader";

function RegisterContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const tabParam = searchParams.get("tab");
  const [activeTab, setActiveTab] = useState<"customer" | "owner">(
    tabParam === "owner" ? "owner" : "customer"
  );
  
  // States for Customer registration
  const [isLoadingCustomer, setIsLoadingCustomer] = useState(false);
  const [showPasswordCustomer, setShowPasswordCustomer] = useState(false);
  const [showConfirmPasswordCustomer, setShowConfirmPasswordCustomer] = useState(false);
  const [isPasswordFocusedCustomer, setIsPasswordFocusedCustomer] = useState(false);

  // States for Owner registration
  const [isLoadingOwner, setIsLoadingOwner] = useState(false);
  const [showPasswordOwner, setShowPasswordOwner] = useState(false);
  const [showConfirmPasswordOwner, setShowConfirmPasswordOwner] = useState(false);
  const [isPasswordFocusedOwner, setIsPasswordFocusedOwner] = useState(false);

  const [isGoogleLoading, setIsGoogleLoading] = useState(false);

  // Forms definition
  const customerForm = useForm<RegisterFormValues>({
    resolver: zodResolver(registerSchema),
    defaultValues: {
      fullName: "",
      email: "",
      phone: "",
      password: "",
      confirmPassword: "",
      terms: false,
    },
  });

  const ownerForm = useForm<RegisterOwnerFormValues>({
    resolver: zodResolver(registerOwnerSchema),
    defaultValues: {
      fullName: "",
      email: "",
      phone: "",
      password: "",
      confirmPassword: "",
      businessName: "",
      taxCode: "",
      businessAddress: "",
      businessLicenseUrl: "",
      identityCardUrl: "",
      terms: false,
    },
  });

  const customerPasswordValue = customerForm.watch("password");
  const ownerPasswordValue = ownerForm.watch("password");

  async function onCustomerSubmit(values: RegisterFormValues) {
    setIsLoadingCustomer(true);
    try {
      await api.post("/auth/register", {
        fullName: values.fullName,
        email: values.email,
        phone: values.phone,
        password: values.password,
        confirmPassword: values.confirmPassword,
      });

      toast.success("Đăng ký khách hàng thành công! Vui lòng kiểm tra email để nhận mã OTP.");
      router.push(`/verify-otp?email=${encodeURIComponent(values.email)}`);
    } catch (error: any) {
      toast.error(error.message || "Đăng ký thất bại");
    } finally {
      setIsLoadingCustomer(false);
    }
  }

  async function onOwnerSubmit(values: RegisterOwnerFormValues) {
    setIsLoadingOwner(true);
    try {
      await api.post("/auth/register/owner", {
        fullName: values.fullName,
        email: values.email,
        phone: values.phone,
        password: values.password,
        confirmPassword: values.confirmPassword,
        businessName: values.businessName,
        taxCode: values.taxCode,
        businessAddress: values.businessAddress,
        businessLicenseUrl: values.businessLicenseUrl,
        identityCardUrl: values.identityCardUrl,
      });

      toast.success("Đăng ký tài khoản đối tác thành công! Vui lòng xác thực email để hoàn tất đăng ký.");
      sessionStorage.setItem("is_owner_registration", "true"); // Flag để verify-otp phân biệt flow Owner vs Customer
      router.push(`/verify-otp?email=${encodeURIComponent(values.email)}`);
    } catch (error: any) {
      toast.error(error.message || "Đăng ký đối tác thất bại");
    } finally {
      setIsLoadingOwner(false);
    }
  }

  const handleGoogleRegister = async () => {
    setIsGoogleLoading(true);
    try {
      await signIn("google", { callbackUrl: "/" });
    } catch {
      toast.error("Đã xảy ra lỗi khi đăng ký bằng Google.");
      setIsGoogleLoading(false);
    }
  };

  const isBusy = isLoadingCustomer || isLoadingOwner || isGoogleLoading;

  return (
    <div className="min-h-screen bg-gradient-to-br from-primary/10 via-background to-primary/5 flex items-center justify-center p-4">
      <Card className="w-full max-w-lg shadow-xl border border-primary/10 backdrop-blur-[2px]">
        <CardHeader className="text-center pb-6">
          <div className="mx-auto w-14 h-14 bg-primary rounded-xl flex items-center justify-center text-white text-2xl mb-3 font-bold shadow-lg shadow-primary/20">
            SV
          </div>
          <CardTitle className="text-2xl font-extrabold tracking-tight">Đăng ký tài khoản</CardTitle>
          <CardDescription>
            Chọn loại tài khoản phù hợp với nhu cầu của bạn
          </CardDescription>
        </CardHeader>

        <CardContent>
          <Tabs defaultValue="customer" onValueChange={(v) => setActiveTab(v as "customer" | "owner")} className="w-full">
            <TabsList className="grid w-full grid-cols-2 mb-6">
              <TabsTrigger value="customer" disabled={isBusy}>Khách hàng đặt sân</TabsTrigger>
              <TabsTrigger value="owner" disabled={isBusy}>Đối tác chủ sân</TabsTrigger>
            </TabsList>

            {/* CUSTOMER TAB CONTENT */}
            <TabsContent value="customer">
              <Form {...customerForm}>
                <form onSubmit={customerForm.handleSubmit(onCustomerSubmit)} className="space-y-4">
                  <FormField
                    control={customerForm.control}
                    name="fullName"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Họ và tên</FormLabel>
                        <FormControl>
                          <div className="relative">
                            <User className="absolute left-3 top-3 h-5 w-5 text-muted-foreground" />
                            <Input placeholder="Nguyễn Văn A" className="pl-10" {...field} disabled={isBusy} />
                          </div>
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />

                  <FormField
                    control={customerForm.control}
                    name="email"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Email</FormLabel>
                        <FormControl>
                          <div className="relative">
                            <Mail className="absolute left-3 top-3 h-5 w-5 text-muted-foreground" />
                            <Input placeholder="your@email.com" className="pl-10" {...field} disabled={isBusy} />
                          </div>
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />

                  <FormField
                    control={customerForm.control}
                    name="phone"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Số điện thoại</FormLabel>
                        <FormControl>
                          <div className="relative">
                            <Phone className="absolute left-3 top-3 h-5 w-5 text-muted-foreground" />
                            <Input placeholder="0901234567" className="pl-10" {...field} disabled={isBusy} />
                          </div>
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />

                  <FormField
                    control={customerForm.control}
                    name="password"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Mật khẩu</FormLabel>
                        <FormControl>
                          <div className="relative">
                            <Lock className="absolute left-3 top-3 h-5 w-5 text-muted-foreground" />
                            <Input
                              type={showPasswordCustomer ? "text" : "password"}
                              placeholder="••••••••"
                              className="pl-10 pr-10"
                              {...field}
                              disabled={isBusy}
                              onFocus={() => setIsPasswordFocusedCustomer(true)}
                              onBlur={(e) => {
                                field.onBlur();
                                setIsPasswordFocusedCustomer(false);
                              }}
                            />
                            <Button
                              type="button"
                              variant="ghost"
                              size="sm"
                              className="absolute right-0 top-0 h-full px-3 py-2 hover:bg-transparent"
                              onClick={() => setShowPasswordCustomer(!showPasswordCustomer)}
                              disabled={isBusy}
                            >
                              {showPasswordCustomer ? (
                                <EyeOff className="h-5 w-5 text-muted-foreground" />
                              ) : (
                                <Eye className="h-5 w-5 text-muted-foreground" />
                              )}
                            </Button>
                          </div>
                        </FormControl>
                        {(customerPasswordValue || isPasswordFocusedCustomer) && (
                          <PasswordStrengthIndicator password={customerPasswordValue} />
                        )}
                        <FormMessage />
                      </FormItem>
                    )}
                  />

                  <FormField
                    control={customerForm.control}
                    name="confirmPassword"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Xác nhận mật khẩu</FormLabel>
                        <FormControl>
                          <div className="relative">
                            <Lock className="absolute left-3 top-3 h-5 w-5 text-muted-foreground" />
                            <Input
                              type={showConfirmPasswordCustomer ? "text" : "password"}
                              placeholder="••••••••"
                              className="pl-10 pr-10"
                              {...field}
                              disabled={isBusy}
                            />
                            <Button
                              type="button"
                              variant="ghost"
                              size="sm"
                              className="absolute right-0 top-0 h-full px-3 py-2 hover:bg-transparent"
                              onClick={() => setShowConfirmPasswordCustomer(!showConfirmPasswordCustomer)}
                              disabled={isBusy}
                            >
                              {showConfirmPasswordCustomer ? (
                                <EyeOff className="h-5 w-5 text-muted-foreground" />
                              ) : (
                                <Eye className="h-5 w-5 text-muted-foreground" />
                              )}
                            </Button>
                          </div>
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />

                  <FormField
                    control={customerForm.control}
                    name="terms"
                    render={({ field }) => (
                      <FormItem className="space-y-1">
                        <div className="flex items-center gap-2">
                          <FormControl>
                            <Checkbox
                              checked={field.value}
                              onCheckedChange={field.onChange}
                              className="shrink-0"
                              disabled={isBusy}
                            />
                          </FormControl>
                          <p className="text-sm font-normal text-muted-foreground leading-none">
                            Tôi đồng ý với{" "}
                            <Link href="#" className="text-primary hover:underline">
                              Điều khoản sử dụng
                            </Link>
                            {" "}và{" "}
                            <Link href="#" className="text-primary hover:underline">
                              Chính sách bảo mật
                            </Link>
                          </p>
                        </div>
                        <FormMessage />
                      </FormItem>
                    )}
                  />

                  <Button className="w-full mt-6" size="lg" type="submit" disabled={isBusy}>
                    {isLoadingCustomer ? (
                      <>
                        <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                        Đang đăng ký khách hàng...
                      </>
                    ) : (
                      "Đăng ký khách hàng"
                    )}
                  </Button>
                </form>
              </Form>
            </TabsContent>

            {/* OWNER TAB CONTENT */}
            <TabsContent value="owner">
              <Form {...ownerForm}>
                <form onSubmit={ownerForm.handleSubmit(onOwnerSubmit)} className="space-y-4">
                  
                  {/* Personal Fields Group */}
                  <div className="bg-primary/5 p-4 rounded-lg space-y-4 border border-primary/10">
                    <h3 className="text-sm font-bold text-primary mb-2">Thông tin tài khoản đại diện</h3>
                    
                    <FormField
                      control={ownerForm.control}
                      name="fullName"
                      render={({ field }) => (
                        <FormItem>
                          <FormLabel>Họ và tên người đại diện</FormLabel>
                          <FormControl>
                            <div className="relative">
                              <User className="absolute left-3 top-3 h-5 w-5 text-muted-foreground" />
                              <Input placeholder="Nguyễn Văn B" className="pl-10" {...field} disabled={isBusy} />
                            </div>
                          </FormControl>
                          <FormMessage />
                        </FormItem>
                      )}
                    />

                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <FormField
                        control={ownerForm.control}
                        name="email"
                        render={({ field }) => (
                          <FormItem>
                            <FormLabel>Email liên hệ</FormLabel>
                            <FormControl>
                              <div className="relative">
                                <Mail className="absolute left-3 top-3 h-5 w-5 text-muted-foreground" />
                                <Input placeholder="owner@business.com" className="pl-10" {...field} disabled={isBusy} />
                              </div>
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                        )}
                      />

                      <FormField
                        control={ownerForm.control}
                        name="phone"
                        render={({ field }) => (
                          <FormItem>
                            <FormLabel>Số điện thoại</FormLabel>
                            <FormControl>
                              <div className="relative">
                                <Phone className="absolute left-3 top-3 h-5 w-5 text-muted-foreground" />
                                <Input placeholder="0901234567" className="pl-10" {...field} disabled={isBusy} />
                              </div>
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                        )}
                      />
                    </div>

                    <FormField
                      control={ownerForm.control}
                      name="password"
                      render={({ field }) => (
                        <FormItem>
                          <FormLabel>Mật khẩu</FormLabel>
                          <FormControl>
                            <div className="relative">
                              <Lock className="absolute left-3 top-3 h-5 w-5 text-muted-foreground" />
                              <Input
                                type={showPasswordOwner ? "text" : "password"}
                                placeholder="••••••••"
                                className="pl-10 pr-10"
                                {...field}
                                disabled={isBusy}
                                onFocus={() => setIsPasswordFocusedOwner(true)}
                                onBlur={(e) => {
                                  field.onBlur();
                                  setIsPasswordFocusedOwner(false);
                                }}
                              />
                              <Button
                                type="button"
                                variant="ghost"
                                size="sm"
                                className="absolute right-0 top-0 h-full px-3 py-2 hover:bg-transparent"
                                onClick={() => setShowPasswordOwner(!showPasswordOwner)}
                                disabled={isBusy}
                              >
                                {showPasswordOwner ? (
                                  <EyeOff className="h-5 w-5 text-muted-foreground" />
                                ) : (
                                  <Eye className="h-5 w-5 text-muted-foreground" />
                                )}
                              </Button>
                            </div>
                          </FormControl>
                          {(ownerPasswordValue || isPasswordFocusedOwner) && (
                            <PasswordStrengthIndicator password={ownerPasswordValue} />
                          )}
                          <FormMessage />
                        </FormItem>
                      )}
                    />

                    <FormField
                      control={ownerForm.control}
                      name="confirmPassword"
                      render={({ field }) => (
                        <FormItem>
                          <FormLabel>Xác nhận mật khẩu</FormLabel>
                          <FormControl>
                            <div className="relative">
                              <Lock className="absolute left-3 top-3 h-5 w-5 text-muted-foreground" />
                              <Input
                                type={showConfirmPasswordOwner ? "text" : "password"}
                                placeholder="••••••••"
                                className="pl-10 pr-10"
                                {...field}
                                disabled={isBusy}
                              />
                              <Button
                                type="button"
                                variant="ghost"
                                size="sm"
                                className="absolute right-0 top-0 h-full px-3 py-2 hover:bg-transparent"
                                onClick={() => setShowConfirmPasswordOwner(!showConfirmPasswordOwner)}
                                disabled={isBusy}
                              >
                                {showConfirmPasswordOwner ? (
                                  <EyeOff className="h-5 w-5 text-muted-foreground" />
                                ) : (
                                  <Eye className="h-5 w-5 text-muted-foreground" />
                                )}
                              </Button>
                            </div>
                          </FormControl>
                          <FormMessage />
                        </FormItem>
                      )}
                    />
                  </div>

                  {/* Business Fields Group */}
                  <div className="bg-secondary/20 p-4 rounded-lg space-y-4 border border-secondary">
                    <h3 className="text-sm font-bold text-foreground mb-2">Thông tin doanh nghiệp/sân bãi</h3>

                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <FormField
                        control={ownerForm.control}
                        name="businessName"
                        render={({ field }) => (
                          <FormItem>
                            <FormLabel>Tên thương hiệu/Doanh nghiệp</FormLabel>
                            <FormControl>
                              <div className="relative">
                                <Building className="absolute left-3 top-3 h-5 w-5 text-muted-foreground" />
                                <Input placeholder="Công ty TNHH Sân bóng Hoàng Gia" className="pl-10" {...field} disabled={isBusy} />
                              </div>
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                        )}
                      />

                      <FormField
                        control={ownerForm.control}
                        name="taxCode"
                        render={({ field }) => (
                          <FormItem>
                            <FormLabel>Mã số thuế</FormLabel>
                            <FormControl>
                              <div className="relative">
                                <FileText className="absolute left-3 top-3 h-5 w-5 text-muted-foreground" />
                                <Input placeholder="0312456789" className="pl-10" {...field} disabled={isBusy} />
                              </div>
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                        )}
                      />
                    </div>

                    <FormField
                      control={ownerForm.control}
                      name="businessAddress"
                      render={({ field }) => (
                        <FormItem>
                          <FormLabel>Địa chỉ đăng ký kinh doanh</FormLabel>
                          <FormControl>
                            <div className="relative">
                              <MapPin className="absolute left-3 top-3 h-5 w-5 text-muted-foreground" />
                              <Input placeholder="Số 12, Đường số 4, Phường Tân Quy, Quận 7" className="pl-10" {...field} disabled={isBusy} />
                            </div>
                          </FormControl>
                          <FormMessage />
                        </FormItem>
                      )}
                    />

                    <div className="flex flex-col gap-4 mt-4">
                      <FormField
                        control={ownerForm.control}
                        name="businessLicenseUrl"
                        render={({ field }) => (
                          <FormItem>
                            <FormControl>
                              <DocumentUploader
                                label="Giấy phép đăng ký kinh doanh (Ảnh)"
                                value={field.value}
                                onChange={field.onChange}
                                disabled={isBusy}
                              />
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                        )}
                      />

                      <FormField
                        control={ownerForm.control}
                        name="identityCardUrl"
                        render={({ field }) => (
                          <FormItem>
                            <FormControl>
                              <DocumentUploader
                                label="Ảnh CCCD/CMND người đại diện"
                                value={field.value}
                                onChange={field.onChange}
                                disabled={isBusy}
                              />
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                        )}
                      />
                    </div>
                  </div>

                  <FormField
                    control={ownerForm.control}
                    name="terms"
                    render={({ field }) => (
                      <FormItem className="space-y-1">
                        <div className="flex items-center gap-2">
                          <FormControl>
                            <Checkbox
                              checked={field.value}
                              onCheckedChange={field.onChange}
                              className="shrink-0"
                              disabled={isBusy}
                            />
                          </FormControl>
                          <p className="text-sm font-normal text-muted-foreground leading-none">
                            Tôi đồng ý với{" "}
                            <Link href="#" className="text-primary hover:underline">
                              Điều khoản dịch vụ đối tác
                            </Link>
                            {" "}và{" "}
                            <Link href="#" className="text-primary hover:underline">
                              Chính sách bảo mật kinh doanh
                            </Link>
                          </p>
                        </div>
                        <FormMessage />
                      </FormItem>
                    )}
                  />

                  <Button className="w-full mt-6" size="lg" type="submit" disabled={isBusy}>
                    {isLoadingOwner ? (
                      <>
                        <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                        Đang đăng ký đối tác...
                      </>
                    ) : (
                      "Đăng ký đối tác chủ sân"
                    )}
                  </Button>
                </form>
              </Form>
            </TabsContent>
          </Tabs>

          {/* Google SSO is only for Customer tab */}
          {activeTab === "customer" && (
            <>
              <div className="relative my-6">
                <Separator />
                <span className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 bg-card px-2 text-sm text-muted-foreground">
                  hoặc
                </span>
              </div>

              <Button
                variant="outline"
                className="w-full"
                size="lg"
                onClick={handleGoogleRegister}
                disabled={isBusy}
              >
                {isGoogleLoading ? (
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                ) : (
                  <Chrome className="mr-2 h-5 w-5" />
                )}
                Đăng ký bằng Google
              </Button>
            </>
          )}

          <p className="text-center text-sm text-muted-foreground mt-6">
            Đã có tài khoản?{" "}
            <Link href="/login" className="text-primary hover:underline font-medium">
              Đăng nhập
            </Link>
          </p>
        </CardContent>
      </Card>
    </div>
  );
}

export default function RegisterPage() {
  return (
    <Suspense fallback={
      <div className="min-h-screen bg-gradient-to-br from-primary/10 via-background to-primary/5 flex items-center justify-center p-4">
        Đang tải...
      </div>
    }>
      <RegisterContent />
    </Suspense>
  );
}
