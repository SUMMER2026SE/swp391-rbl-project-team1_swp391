"use client";

import { useState, useEffect } from "react";
import { get, post } from "@/lib/api";
import { Star, Loader2, MessageSquare, Send } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { toast } from "sonner";

interface ReviewResponse {
  reviewId: number;
  bookingId: number;
  stadiumId: number;
  reviewerName: string;
  ratingScore: number;
  comment: string;
  ownerResponse: string;
  createdAt: string;
}

interface PageResponse<T> {
  content: T[];
  totalPages: number;
  totalElements: number;
  size: number;
  number: number;
}

export function OwnerReviewHistoryList() {
  const [reviews, setReviews] = useState<ReviewResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  
  const [replyingTo, setReplyingTo] = useState<number | null>(null);
  const [replyText, setReplyText] = useState("");
  const [submittingReply, setSubmittingReply] = useState(false);

  const fetchReviews = async () => {
    try {
      setLoading(true);
      const data = await get<PageResponse<ReviewResponse>>("/owner/reviews?page=0&size=50&sort=createdAt,desc");
      setReviews(data.content);
    } catch (err: any) {
      setError(err.message || "Không thể tải danh sách đánh giá từ khách hàng");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchReviews();
  }, []);

  const handleReplySubmit = async (reviewId: number) => {
    if (!replyText.trim()) return;
    try {
      setSubmittingReply(true);
      await post(`/owner/reviews/${reviewId}/reply`, { replyMessage: replyText.trim() });
      setReplyText("");
      setReplyingTo(null);
      // Refresh the list after replying
      await fetchReviews();
    } catch (err: any) {
      toast.error(err.message || "Không thể gửi phản hồi. Vui lòng thử lại.");
    } finally {
      setSubmittingReply(false);
    }
  };

  if (loading) {
    return (
      <div className="flex justify-center items-center py-12">
        <Loader2 className="h-8 w-8 animate-spin text-primary" />
      </div>
    );
  }

  if (error) {
    return (
      <div className="text-center py-8 text-red-500">
        <p>{error}</p>
      </div>
    );
  }

  if (reviews.length === 0) {
    return (
      <Card className="border-none shadow-sm bg-white p-8 text-center">
        <MessageSquare className="h-16 w-16 text-slate-300 mx-auto mb-4" />
        <h3 className="text-lg font-bold text-slate-800 mb-2">Chưa có đánh giá nào</h3>
        <p className="text-slate-500 text-sm">Các sân của bạn chưa có đánh giá nào từ khách hàng.</p>
      </Card>
    );
  }

  return (
    <div className="space-y-4">
      {reviews.map((review) => (
        <Card key={review.reviewId} className="border-slate-100 shadow-sm hover:shadow-md transition-shadow">
          <CardContent className="p-5">
            <div className="flex justify-between items-start mb-3">
              <div>
                <h4 className="font-semibold text-slate-800 flex items-center gap-2">
                  {review.reviewerName} (Mã đặt sân: #{review.bookingId})
                  <Badge variant="outline" className="text-xs font-normal">
                    {new Date(review.createdAt).toLocaleDateString("vi-VN")}
                  </Badge>
                </h4>
              </div>
              <div className="flex text-amber-400">
                {[1, 2, 3, 4, 5].map((star) => (
                  <Star
                    key={star}
                    className={`h-4 w-4 ${star <= review.ratingScore ? "fill-current" : "text-slate-200"}`}
                  />
                ))}
              </div>
            </div>
            {review.comment && (
              <p className="text-slate-600 text-sm italic border-l-2 border-slate-200 pl-3">
                &quot;{review.comment}&quot;
              </p>
            )}
            
            {review.ownerResponse ? (
              <div className="mt-4 bg-teal-50 border border-teal-100 rounded-lg p-3 text-sm">
                <p className="font-semibold text-teal-800 text-xs mb-1">Phản hồi của bạn:</p>
                <p className="text-teal-700">{review.ownerResponse}</p>
              </div>
            ) : (
              <div className="mt-4">
                {replyingTo === review.reviewId ? (
                  <div className="space-y-3">
                    <Textarea 
                      placeholder="Nhập phản hồi của bạn..."
                      value={replyText}
                      onChange={(e) => setReplyText(e.target.value)}
                      className="text-sm min-h-[80px]"
                    />
                    <div className="flex gap-2 justify-end">
                      <Button 
                        variant="outline" 
                        size="sm" 
                        onClick={() => {
                          setReplyingTo(null);
                          setReplyText("");
                        }}
                      >
                        Hủy
                      </Button>
                      <Button 
                        size="sm" 
                        onClick={() => handleReplySubmit(review.reviewId)}
                        disabled={!replyText.trim() || submittingReply}
                      >
                        {submittingReply ? <Loader2 className="h-4 w-4 animate-spin mr-1" /> : <Send className="h-4 w-4 mr-1" />}
                        Gửi phản hồi
                      </Button>
                    </div>
                  </div>
                ) : (
                  <Button 
                    variant="outline" 
                    size="sm" 
                    onClick={() => setReplyingTo(review.reviewId)}
                    className="text-teal-600 border-teal-200 hover:bg-teal-50"
                  >
                    Phản hồi khách hàng
                  </Button>
                )}
              </div>
            )}
          </CardContent>
        </Card>
      ))}
    </div>
  );
}
